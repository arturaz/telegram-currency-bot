import scala.util.Try

package object implicits {
  implicit class ComparableOps[A](c1: Comparable[A]) {
    def <(c2: A): Boolean = c1.compareTo(c2) < 0
    def <=(c2: A): Boolean = c1.compareTo(c2) <= 0
    def ===(c2: A): Boolean = c1.compareTo(c2) == 0
    def >=(c2: A): Boolean = c1.compareTo(c2) >= 0
    def >(c2: A): Boolean = c1.compareTo(c2) > 0
  }

  object BigDecimalE {
    def unapply(arg: String): Option[BigDecimal] = Try(BigDecimal(arg)).toOption
  }
}
