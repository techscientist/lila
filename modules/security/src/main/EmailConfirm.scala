package lila.security

import akka.actor.ActorSystem
import com.roundeights.hasher.{ Hasher, Algo }
import play.api.libs.ws.{ WS, WSAuthScheme }
import play.api.Play.current
import scala.concurrent.duration._

import lila.common.String.base64
import lila.user.{ User, UserRepo }

trait EmailConfirm {

  def effective: Boolean

  def send(user: User, email: String, tryNb: Int = 1): Funit

  def confirm(token: String): Fu[Option[User]]
}

object EmailConfirmSkip extends EmailConfirm {

  def effective = false

  def send(user: User, email: String, tryNb: Int = 1) = UserRepo setEmailConfirmed user.id

  def confirm(token: String): Fu[Option[User]] = fuccess(none)
}

final class EmailConfirmMailGun(
    apiUrl: String,
    apiKey: String,
    sender: String,
    baseUrl: String,
    secret: String,
    system: ActorSystem) extends EmailConfirm {

  def effective = true

  val maxTries = 3

  def send(user: User, email: String, tryNb: Int = 1): Funit = tokener make user flatMap { token =>
    lila.mon.email.confirmation()
    val url = s"$baseUrl/signup/confirm/$token"
    WS.url(s"$apiUrl/messages").withAuth("api", apiKey, WSAuthScheme.BASIC).post(Map(
      "from" -> Seq(sender),
      "to" -> Seq(email),
      "subject" -> Seq(s"Confirm your lichess.org account, ${user.username}"),
      "text" -> Seq(s"""
Final step!

Confirm your email address to complete your lichess account. It's easy — just click on the link below.

$url


Please do not reply to this message; it was sent from an unmonitored email address. This message is a service email related to your use of lichess.org.
"""))).void addFailureEffect {
      case e: java.net.ConnectException => lila.mon.http.mailgun.timeout()
      case _                            =>
    } recoverWith {
      case e if tryNb < maxTries => akka.pattern.after(15 seconds, system.scheduler) {
        send(user, email, tryNb + 1)
      }
      case e => fufail(e)
    }
  }

  def confirm(token: String): Fu[Option[User]] = tokener read token flatMap {
    case u@Some(user) => UserRepo setEmailConfirmed user.id inject u
    case _            => fuccess(none)
  }

  private object tokener {

    private val separator = '|'

    private def makeHash(msg: String) = Algo.hmac(secret).sha1(msg).hex take 14
    private def getHashedEmail(userId: User.ID) = UserRepo email userId map { p =>
      makeHash(~p) take 6
    }
    private def makePayload(userId: String, passwd: String) = s"$userId$separator$passwd"

    def make(user: User) = getHashedEmail(user.id) map { hashedEmail =>
      val payload = makePayload(user.id, hashedEmail)
      val hash = makeHash(payload)
      val token = s"$payload$separator$hash"
      base64 encode token
    }

    def read(token: String): Fu[Option[User]] = (base64 decode token) ?? {
      _ split separator match {
        case Array(userId, userHashedEmail, hash) if makeHash(makePayload(userId, userHashedEmail)) == hash =>
          getHashedEmail(userId) flatMap { hashedEmail =>
            (userHashedEmail == hashedEmail) ?? (UserRepo enabledById userId)
          }
        case _ => fuccess(none)
      }
    }
  }
}
