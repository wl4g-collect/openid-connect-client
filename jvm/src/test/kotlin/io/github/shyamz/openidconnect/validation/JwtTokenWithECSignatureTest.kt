package io.github.shyamz.openidconnect.validation

import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.shyamz.openidconnect.TestConstants.USER_ID
import io.github.shyamz.openidconnect.TestConstants.loadClientConfiguration
import io.github.shyamz.openidconnect.configuration.model.TokenEndPointAuthMethod
import io.github.shyamz.openidconnect.exceptions.OpenIdConnectException
import io.github.shyamz.openidconnect.mocks.stubForKeysEndpoint
import io.github.shyamz.openidconnect.mocks.stubForMockIdentityProvider
import org.assertj.core.api.Assertions.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*


class JwtTokenWithECSignatureTest {

    @JvmField
    @Rule
    val wireMockRule = WireMockRule(8089)

    private lateinit var publicKey: ECPublicKey
    private lateinit var privateKey: ECPrivateKey
    private lateinit var keyId: String
    private lateinit var jwKeySet: JWKSet

    @Before
    fun setUp() {
        stubForMockIdentityProvider()
        loadClientConfiguration("http://localhost:8089", TokenEndPointAuthMethod.Basic)
        createKeys()
        createJwkSet()
        stubForKeysEndpoint(jwKeySet)
        SignatureVerifierFactory.cache.invalidateAll()
    }

    @After
    fun tearDown() {
        SignatureVerifierFactory.cache.invalidateAll()
    }

    @Test
    fun `can validate a valid IdToken`() {

        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience("client-id")
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(Date())
                .build()
                .toIdToken()

        val claims = JwtToken(idToken).claims()

        assertThat(claims.claims).isNotEmpty
        assertThat(claims.claims).contains(entry("sub", USER_ID))
    }

    @Test
    fun `throws exception for an invalid jwt token`() {

        assertThatThrownBy { JwtToken("this.isa.junktoken").claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessage("'this.isa.junktoken' is an invalid JWT token")
    }

    @Test
    fun `throws exception for an invalid signature`() {
        val tokenWithDifferentKey = idTokenWithDifferentKey()

        assertThatThrownBy { JwtToken(tokenWithDifferentKey).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessage("Malicious Token. signature verification failed for token: \n'$tokenWithDifferentKey'")
    }

    @Test
    fun `throws exception when id token is not a valid jwt`() {

        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience("client-id")
                .issuer("https://www.google.com")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(Date())
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessage("Expected issuer 'https://www.google.com' in id_token to match well known config issuer 'http://localhost:8089'")
    }

    @Test
    fun `throws exception when audience claim is not equal to client id from configuration`() {

        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience("another-client-id")
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(Date())
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessage("Expected audience '[another-client-id]' in id_token to contain client 'client-id'")

    }

    @Test
    fun `throws exception when azp claim is not present for multiple audiences`() {

        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience(listOf("client-id", "another-client-id"))
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(Date())
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessage("Expected id_token with multiple audiences '[client-id, another-client-id]' to have an 'azp' claim. But found none")

    }


    @Test
    fun `throws exception when azp claim is not equal to client id from configuration`() {

        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience(listOf("client-id", "another-client-id"))
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(Date())
                .claim("azp", "another-client-id")
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessage("Expected azp 'another-client-id' in id_token to match client id 'client-id'")

    }

    @Test
    fun `throws exception for an expired token`() {

        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience(listOf("client-id", "another-client-id"))
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().minus(1, ChronoUnit.DAYS)))
                .issueTime(Date())
                .claim("azp", "client-id")
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessageContaining("id_token expired")

    }

    @Test
    fun `throws exception when nonce do not match`() {

        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience(listOf("client-id", "another-client-id"))
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)))
                .issueTime(Date())
                .claim("azp", "client-id")
                .claim("nonce", "nonce-value")
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken, "another-nonce-value").claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessage("Expected nonce 'nonce-value' in id_token to match stored nonce 'another-nonce-value'")

    }

    @Test
    fun `throws exception when issued at time has a difference of more or less than two minutes from current time`() {

        val sixMinutesFromNow = Date.from(Instant.now().plus(6, ChronoUnit.MINUTES))
        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience(listOf("client-id", "another-client-id"))
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(sixMinutesFromNow)
                .claim("azp", "client-id")
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessageContaining("id_token is issued at '$sixMinutesFromNow' is too far away from the current time")

    }

    @Test
    fun `throws exception when user last authenticated time is more than five minutes`() {

        val sixMinutesBefore = Date.from(Instant.now().minus(6, ChronoUnit.MINUTES))
        val idToken = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience(listOf("client-id", "another-client-id"))
                .issuer("http://localhost:8089")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(Date())
                .claim("auth_time", sixMinutesBefore)
                .claim("azp", "client-id")
                .build()
                .toIdToken()

        assertThatThrownBy { JwtToken(idToken).claims() }
                .isInstanceOf(OpenIdConnectException::class.java)
                .hasMessageContaining("User last authenticated was '6' minutes before. Re authenticate the user")

    }

    fun JWTClaimsSet.toIdToken(): String {
        val jwsHeader = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build()

        return SignedJWT(jwsHeader, this).apply {
            sign(ECDSASigner(privateKey))
        }.serialize()
    }

    private fun idTokenWithDifferentKey(): String {
        val keyPair = KeyPairGenerator.getInstance("EC").also {
            it.initialize(Curve.P_256.toECParameterSpec())
        }.generateKeyPair()

        val anotherPrivateKey = keyPair.private as ECPrivateKey

        val claims = JWTClaimsSet.Builder()
                .subject("user-id")
                .audience("client-id")
                .issuer("https://www.google.com")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .issueTime(Date())
                .build()

        val jwsHeader = JWSHeader.Builder(JWSAlgorithm.ES256)
                .keyID(keyId)
                .build()

        return SignedJWT(jwsHeader, claims).apply {
            sign(ECDSASigner(anotherPrivateKey))
        }.serialize()
    }


    fun createKeys() {
        val keyPair = KeyPairGenerator.getInstance("EC").also {
            it.initialize(Curve.P_256.toECParameterSpec())
        }.generateKeyPair()

        publicKey = keyPair.public as ECPublicKey
        privateKey = keyPair.private as ECPrivateKey
    }

    fun createJwkSet() {
        keyId = UUID.randomUUID().toString()

        val jwk = ECKey.Builder(Curve.P_256, publicKey)
                .keyID(keyId)
                .build()

        jwKeySet = JWKSet(jwk)
    }
}