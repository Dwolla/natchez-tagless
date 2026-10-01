package com.dwolla.tagless

/** Extracts the domain error from a `Throwable` that is really a cats-mtl
  * raise in transit.
  *
  * `Handle.allowF` over a `MonadThrow` encodes `R.raise(e)` as
  * `F.raiseError(Submarine(e, marker))`, so a raise that escapes an
  * instrumented method reaches spans and metrics as cats-mtl's `Submarine`,
  * which names neither the error type nor its value. `Submarine` is
  * `private[mtl]`, so it is recognized by its runtime class name; it is a case
  * class, so the error is its first product element. `RaisedErrorSpec` pins
  * this against the real cats-mtl, so a rename upstream fails there first.
  * (typelevel/cats-mtl#648 tracks exposing it.)
  */
object RaisedError {
  private val SubmarineClassName = "cats.mtl.Handle$Submarine"

  def unapply(throwable: Throwable): Option[Any] =
    throwable match {
      case submarine: Product if throwable.getClass.getName == SubmarineClassName && submarine.productArity > 0 =>
        Some(submarine.productElement(0))
      case _ => None
    }
}
