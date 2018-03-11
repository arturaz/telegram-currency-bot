import java.time.DayOfWeek

import play.api.libs.json.Format
import play.api.libs.functional.syntax._

import scala.util.Try

package object implicits {
  implicit class ComparableOps[A](val c1: Comparable[A]) extends AnyVal {
    def <(c2: A): Boolean = c1.compareTo(c2) < 0
    def <=(c2: A): Boolean = c1.compareTo(c2) <= 0
    def ===(c2: A): Boolean = c1.compareTo(c2) == 0
    def >=(c2: A): Boolean = c1.compareTo(c2) >= 0
    def >(c2: A): Boolean = c1.compareTo(c2) > 0
  }

  implicit class TraversableOnceOps[A](val to: TraversableOnce[A]) extends AnyVal {
    def collectFind[B](f: A => Option[B]): Option[B] = {
      to.foreach { a => f(a) match {
        case None =>
        case some @ Some(_) => return some
      } }
      None
    }
  }

  implicit val DayOfWeekFormat: Format[DayOfWeek] =
    implicitly[Format[Int]].inmap(DayOfWeek.of, _.getValue)

  object IntE {
    def unapply(arg: String): Option[Int] = Try(arg.toInt).toOption
  }
  object BigDecimalE {
    def unapply(arg: String): Option[BigDecimal] = Try(BigDecimal(arg)).toOption
  }
  object DayOfWeekE {
    def unapply(arg: Int): Option[DayOfWeek] = Try(DayOfWeek.of(arg)).toOption
  }
}
