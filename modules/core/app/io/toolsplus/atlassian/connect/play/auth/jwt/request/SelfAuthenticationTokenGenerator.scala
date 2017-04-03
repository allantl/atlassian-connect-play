package io.toolsplus.atlassian.connect.play.auth.jwt.request

import java.time.Duration
import java.time.temporal.ChronoUnit

import com.google.inject.Inject
import io.toolsplus.atlassian.connect.play.api.models.AtlassianHostUser
import io.toolsplus.atlassian.connect.play.models.{
  AddonProperties,
  AtlassianConnectProperties
}
import io.toolsplus.atlassian.jwt.JwtSigningError
import io.toolsplus.atlassian.jwt.api.Predef.RawJwt

/**
  * Generator of JSON Web Tokens for authenticating requests from the
  * add-on to itself.
  */
class SelfAuthenticationTokenGenerator @Inject()(
    addonProperties: AddonProperties,
    connectProperties: AtlassianConnectProperties) {

  def createSelfAuthenticationToken(
      hostUser: AtlassianHostUser): Either[JwtSigningError, RawJwt] = {
    val expirationAfter = Duration.of(
      connectProperties.selfAuthenticationExpirationTime,
      ChronoUnit.SECONDS)
    val jwt = new JwtBuilder(expirationAfter)
      .withIssuer(addonProperties.key)
      .withAudience(Seq(addonProperties.key))
      .withClaim(SelfAuthenticationTokenGenerator.HOST_CLIENT_KEY_CLAIM,
                 hostUser.host.clientKey)
    hostUser.userKey.map(jwt.withSubject)
    jwt.build(hostUser.host.sharedSecret)
  }
}

object SelfAuthenticationTokenGenerator {

  /**
    * The name of the JWT claim used for the client key of the Atlassian host
    * in self-authentication tokens.
    */
  val HOST_CLIENT_KEY_CLAIM = "clientKey"
}
