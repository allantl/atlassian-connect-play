package io.toolsplus.atlassian.connect.play.auth.jwt

import cats.data.EitherT
import cats.implicits._
import com.google.inject.Inject
import com.nimbusds.jwt.JWTClaimsSet
import io.toolsplus.atlassian.connect.play.api.models.Predefined.AppKey
import io.toolsplus.atlassian.connect.play.api.models.{
  AppProperties,
  AtlassianHost,
  AtlassianHostUser,
  DefaultAtlassianHostUser
}
import io.toolsplus.atlassian.connect.play.api.repositories.AtlassianHostRepository
import io.toolsplus.atlassian.jwt.{
  HttpRequestCanonicalizer,
  Jwt,
  JwtParser,
  JwtReader
}
import play.api.Logger

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class JwtAuthenticationProvider @Inject()(
    hostRepository: AtlassianHostRepository,
    addonConfiguration: AppProperties) {

  private val logger = Logger(classOf[JwtAuthenticationProvider])

  def authenticate(jwtCredentials: JwtCredentials)
    : EitherT[Future, JwtAuthenticationError, AtlassianHostUser] =
    for {
      jwt <- parseJwt(jwtCredentials.rawJwt).toEitherT[Future]
      clientKey <- extractClientKey(jwt).toEitherT[Future]
      host <- fetchAtlassianHost(clientKey)
      verifiedToken <- verifyJwt(jwtCredentials, host).toEitherT[Future]
    } yield
      DefaultAtlassianHostUser(host, Option(verifiedToken.claims.getSubject))

  private def parseJwt(rawJwt: String): Either[JwtAuthenticationError, Jwt] =
    JwtParser.parse(rawJwt).leftMap { e =>
      logger.error(s"Parsing of JWT failed: $e")
      InvalidJwtError(e.getMessage())
    }

  private def extractClientKey(
      jwt: Jwt): Either[JwtAuthenticationError, String] = {
    val unverifiedClaims = jwt.claims
    val addonKey = addonConfiguration.key
    if (isSelfAuthenticationToken(addonKey, unverifiedClaims)) {
      validateSelfAuthenticationTokenAudience(addonKey, unverifiedClaims)
        .flatMap { _ =>
          hostClientKeyFromSelfAuthenticationToken(unverifiedClaims)
        }
    } else {
      hostClientKeyFromAtlassianToken(Option(unverifiedClaims.getIssuer))
    }
  }

  private def fetchAtlassianHost(clientKey: String)
    : EitherT[Future, JwtAuthenticationError, AtlassianHost] = {
    EitherT[Future, JwtAuthenticationError, AtlassianHost](
      hostRepository.findByClientKey(clientKey).map {
        case Some(host) => Right(host)
        case None =>
          logger.error(
            s"Could not find an installed host for the provided client key: $clientKey")
          Left(UnknownJwtIssuerError(clientKey))
      }
    )
  }

  private def verifyJwt(
      jwtCredentials: JwtCredentials,
      host: AtlassianHost): Either[JwtAuthenticationError, Jwt] = {
    val qsh = HttpRequestCanonicalizer.computeCanonicalRequestHash(
      jwtCredentials.canonicalHttpRequest)

    JwtReader(host.sharedSecret)
      .readAndVerify(jwtCredentials.rawJwt, qsh)
      .leftMap { e =>
        logger.error(s"Reading and validating of JWT failed: $e")
        InvalidJwtError(e.getMessage)
      }
  }

  private def isSelfAuthenticationToken(
      addonKey: AppKey,
      unverifiedClaims: JWTClaimsSet): Boolean =
    addonKey == unverifiedClaims.getIssuer

  private def validateSelfAuthenticationTokenAudience(
      addonKey: AppKey,
      unverifiedClaims: JWTClaimsSet)
    : Either[JwtAuthenticationError, List[String]] = {
    unverifiedClaims.getAudience.asScala.toList match {
      case audience @ maybeAddonKey :: Nil =>
        if (maybeAddonKey == addonKey) Right(audience)
        else
          Left(JwtBadCredentialsError(
            s"Invalid audience ($maybeAddonKey) for self-authentication token"))
      case audience @ List(_) =>
        Left(JwtBadCredentialsError(
          s"Invalid audience (${audience.mkString(",")}) for self-authentication token"))
      case Nil =>
        Left(
          JwtBadCredentialsError(
            "Missing audience for self-authentication token"))
    }
  }

  private def hostClientKeyFromSelfAuthenticationToken(
      unverifiedClaims: JWTClaimsSet)
    : Either[JwtAuthenticationError, String] = {
    val maybeClientKeyClaim = Option(
      unverifiedClaims
        .getClaim(SelfAuthenticationTokenGenerator.HostClientKeyClaim)
        .asInstanceOf[String]
    )
    validateSelfAuthenticationTokenClientKey(maybeClientKeyClaim)
  }

  private def validateSelfAuthenticationTokenClientKey(
      maybeClientKeyClaim: Option[String])
    : Either[JwtAuthenticationError, String] = {
    maybeClientKeyClaim match {
      case Some(clientKeyClaim) => Right(clientKeyClaim)
      case None =>
        Left(
          JwtBadCredentialsError(
            "Missing client key claim for self-authentication token"
          )
        )
    }
  }

  private def hostClientKeyFromAtlassianToken(
      maybeClientKeyClaim: Option[String])
    : Either[JwtAuthenticationError, String] = {
    maybeClientKeyClaim match {
      case Some(clientKeyClaim) => Right(clientKeyClaim)
      case None =>
        Left(
          JwtBadCredentialsError("Missing client key claim for Atlassian token")
        )
    }
  }

}
