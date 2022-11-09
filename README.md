# Natchez Tagless

Semi-automatically derive [Natchez](http://tpolecat.github.io/natchez/) trace instrumentation for algebras supported by [cats-tagless](https://typelevel.org/cats-tagless/).

## Concepts

All the examples below have the following imports:
```scala
import cats._, cats.syntax.all._, cats.effect._, cats.tagless._, cats.tagless.aop._, cats.tagless.syntax.all._, cats.effect.std._, cats.effect.unsafe.implicits.global, natchez.Trace.Implicits.noop, com.dwolla.tracing._
```

### Programs as Values

Each instance of a Cats Effect `IO` is essentially a little program—this is the core of the concept of "programs as values." If we have an `IO[Int]`, that represents a program that will result in an `Int` value being calculated when it's run.

```scala
scala> val x: IO[Int] = IO(45 - 3)
val x: cats.effect.IO[Int] = IO(…)

scala> x.unsafeRunSync()
val res0: Int = 42
```

It isn't until we actually run the `x` program that the result is calculated and `42` is returned. You could think of this as being similar to how there are lots of programs on your computer that will perform calculations and return results when executed—but not before!

### Capturing Metadata

So an `IO[_]` is a program that can be run to yield a value or perform some effect in the world. Since we can assign it to variables, we could theoretically stick it inside another class that contains metadata about the program:

```scala
scala> case class IOMetadata(name: String, value: IO[Int])
class IOMetadata

scala> val metadata = IOMetadata("the answer", x)
val metadata: IOMetadata = IOMetadata(the answer,IO(…))
```

(Note that the string representation of the `metadata` variable printed to the console by the REPL shows the actual value of the `name` field, but the `value` field is shown as `IO(…)` since it hasn't been calculated yet.)

### Use the Metadata to Enhance the Original Program

Maybe we want to always print the name of the program before executing our `value: IO[_]`. We could convert the `IOMetadata` into another `IO` that combines the two effects, first printing the name, and then calculating the value:

```scala
def printNameAndThenCalculate(m: IOMetadata): IO[Int] =
  IO.println(m.name) >> m.value
```

We can apply this to our `metadata: IOMetadata` value from above, and then execute it (because again, none of the `IO` values are actually executed until we run them!):

```scala
scala> val xWithName: IO[Int] = printNameAndThenCalculate(metadata)
val xWithName: cats.effect.IO[Int] = IO(…)

scala> xWithName.unsafeRunSync()
the answer
val res1: Int = 42
```

Or, if we have a `Trace[IO]` in scope, we could use the metadata to help define a new span in which to run the program:

```scala
def traceWithIOMetadata(m: IOMetadata): IO[Int] =
  Trace[IO].span(m.name)(m.value)
```

```scala
scala> traceWithIOMetadata(metadata)
val res2: IO[Int] = IO(…)
```

What if we don't always want to calculate `Int` values? We can tweak the definitions above so that they'll work with any type:

```scala
case class IOMetadata[A](name: String, value: IO[A])

def printNameAndThenCalculate[A](m: IOMetadata[A]): IO[A] =
  IO.println(m.name) >> m.value
```

```scala
scala> val metadata = IOMetadata("say hello", IO("hello").flatTap(IO.println(_)))
val metadata: IOMetadata[String] = IOMetadata(say hello,IO(...))

scala> printNameAndThenCalculate(metadata).unsafeRunSync()
say hello
hello
val res3: String = hello
```

Note that the return types now contain `String` instead of `Int` as before!

### Abstract Types

We've shown that we can attach metadata to an `IO` by putting both the metadata and the `IO` into a wrapper class, and how to use that wrapper class to run the `IO` in the context of a trace. But so far, this has been a pretty manual process—as the programmer, we had to define the string value to be used as the name and then place it and the `IO` into an `IOMetadata` instance. There's a way to largely avoid this boilerplate, though, and it involves using abstract higher kinded types and some metaprogramming implemented in the cats-tagless library.

First, a brief introduction to the abstract higher kinded types, and how this stuff fits together. Instead of hard-coding the `IO` effect type, we can introduce type variables into the methods we defined above, so they work with any type that has the right "shape." For example,

```scala
case class EffectMetadata[F[_], A](name: String, value: F[A])

def printNameAndThenCalculate[F[_] : FlatMap : Console, A](m: EffectMetadata[F, A]): F[A] =
  Console[F].println(m.name) >> m.value
```

Both the `EffectMetadata` case class and the `printNameAndThenCalculate` method define an `F[_]` type variable. This syntax means `F[_]` can be any type that itself requires another type to be fully defined. `IO` is one example, but some other examples from the Scala stdlib include `Option` and `List`. In all these cases, just knowing something is an `IO`, `Option`, or `List` isn't enough to fully define the type—you need to fill in those holes, like `IO[Int]`, `Option[String]`, or `List[Boolean]`.

If `F[_]` can be literally any type that fits that shape, you can't do much with it as a programmer. For this reason, in `printNameAndThenCalculate` we specify two type constraints. `F[_] : Foo` essentially means "any type with a single hole for which exists an instance of `Foo[F]`."

In order for a call to `printNameAndThenCalculate(EffectMetadata("name", fa: F[A])` to compile, the compiler must be able to find and identify an instance of `FlatMap[F]` and `Console[F]` for whatever type `F[_]` is. For example, there exists an instance of `FlatMap[Option]`, but not an instance of `Console[Option]`, meaning we couldn't call `printNameAndThenCalculate` and use `Option` as the `F[_]`. On the other hand, both `FlatMap[IO]` and `Console[IO]` exist, so we _can_ call `printNameAndThenCalculate` with an `IO[_]`.

Since we constrained the `F[_]` used for a call to `printNameAndThenCalculate`, inside the body of the method we can write code that assumes those constraints. `Console[F].println(m.name)` summons the instance of `Console[F]` and calls that instance's `println` method. `>>` is an alias for `flatMap`, which relies on the instance of `FlatMap[F]`. (This can be equivalently rewritten as `FlatMap[F].flatMap(Console[F].println(m.name))(_ => m.value)` to show the summoning of `FlatMap[F]`, but most Scala developers would not use that style.

### Cats Tagless Transformations

Let's say we have an interface for calculating the answer to the Ultimate Question of Life, the Universe, and Everything!

```scala
trait DeepThought[F[_]] {
  def answer: F[Int]
}
```

And an implementation of that interface:

```scala
object DeepThought {
  def apply[F[_] : Applicative]: DeepThought[F] = new DeepThought[F] {
    def answer: F[Int] = 42.pure[F]
  }
}
```

We can run the supercomputer using `IO` as our effect type, and it will return the answer:

```scala
scala> DeepThought[IO].answer.unsafeRunSync()
val res4: Int = 42
```

Cats Tagless defines a metadata wrapper called `Instrumentation` that captures information similar to our `EffectMetadata` class above:

```scala
final case class Instrumentation[F[_], A](value: F[A], algebraName: String, methodName: String)
```

(Don't worry about the word "algebra" here; there are deeper reasons why it's related to the algebra we learned in school, but for our purposes, we just mean an interface with a higher kinded type parameter, where each method returns a value of that higher kinded type parameter.)

An `Instrumentation` of the method call `DeepThought[IO].answer` would be

```scala
Instrumentation(DeepThought[IO].answer, "DeepThought", "methodName")
```

Cats Tagless also defines a typeclass called `Instrument` which transforms an instance of an algebra implemented in `F[_]` to an instance implemented in `Instrumentation[F, *]`.

(`Instrumentation[F, *]` is a higher kinded type with a single hole—in other words, the same "shape" as `F[_]`. `Instrumentation` itself has two type parameters: `F[_]` and `A`. We can turn it into a higher kinded type with a single hole by filling in one of the two holes. This is also called partial application of the type parameters.)

We could implement an instance of `Instrument` for our `DeepThought` algebra:

```scala
implicit val deepThoughtInstrument: Instrument[DeepThought] = new Instrument[DeepThought] {
  def instrument[F[_]](af: DeepThought[F]): DeepThought[Instrumentation[F, *]] = new DeepThought[Instrumentation[F, *]] {
    def answer: Instrumentation[F, Int] = {
      val value: F[Int] = af.answer
      val algebraName: String = "DeepThought"
      val methodName: String = "answer"
      Instrumentation(value, algebraName, methodName)
    }
  }
}
```

You could imagine that with lots of algebras to instrument, or large algebras with lots of methods, manually writing out `Instrument` instances using brute force could get pretty tedious. Luckily, Cats Tagless comes with derivation macros that can usually do most of the work for us:

```scala
implicit val deepThoughtInstrument: Instrument[DeepThought] = cats.tagless.Derive.instrument
```

Ideally that `implicit val` would be placed in the companion object to the algebra (i.e., next to the `def apply` method above in `object DeepThought`) so that the compiler can find it during a search of the implicit scope.

Having a `DeepThought[Instrumentation[F, *]]` isn't super helpful on its own, because `Instrumentation[F, *]` just captures metadata. It becomes very useful when the `Instrumentation[F, *]` can be transformed back to an `F[_]` using something that can take advantage of the metadata. If we tweak our `traceWithIOMetadata` function above, it can do just that!

```scala
def traceWithInstrumentation[F[_] : Trace, A](fa: Instrumentation[F, A]): F[A] =
  Trace[F].span(s"${fa.algebraName}.${fa.methodName}")(fa.value)
```

We can apply this method throughout the algebra to convert `DeepThought[Instrumentation[F, *]]` back to `DeepThought[F]`:

```scala
def traceInstrumentedDeepThought[F[_] : Trace](fa: DeepThought[Instrumentation[F, *]]): DeepThought[F] = new DeepThought[F] {
  def answer: F[Int] = {
    val instrumentation: Instrumentation[F, Int] = fa.answer
    traceWithInstrumentation(instrumentation)
  }
}
```

but again, that's a lot of boilerplate. Luckily, `traceWithInstrumentation` can be written as a natural transformation:

```scala
class TraceInstrumentation[F[_] : Trace] extends (Instrumentation[F, *] ~> F) {
  override def apply[A](fa: Instrumentation[F, A]): F[A] =
    Trace[F].span(s"${fa.algebraName}.${fa.methodName}")(fa.value)
}
```

and then applied to our `DeepThought[Instrumentation[F, *]]` using `mapK` (which comes for free since we were able to define `Instrument[DeepThought]` above):

```scala
scala> DeepThought[IO]
val res5: DeepThought[cats.effect.IO] = DeepThought$$anon$4@5455ec6

scala> res5.instrument
val res6: DeepThought[[β$1$]cats.tagless.aop.Instrumentation[[+A]cats.effect.IO[A],β$1$]] = DeepThought$$anon$1$$anon$2@2a915583

scala> res6.mapK(new com.dwolla.tracing.TraceInstrumentation[IO])
val res7: DeepThought[cats.effect.IO] = DeepThought$$anon$1$$anon$3@252d03a9
```

and in fact, `TraceInstrumentation` is made available by this library (see `com.dwolla.tracing.TraceInstrumentation`).

### Capturing Method Parameters and Return Values

The `Instrumentation` class only captures the primary effect value, algebra name, and method name, meaning method parameters are not available. Cats Tagless has a more powerful metadata class called `Weave`, which can capture inputs in certain contexts.

```scala
final case class Weave[F[_], Dom[_], Cod[_], A](algebraName: String,
                                                domain: List[List[Advice[Eval, Dom]]],
                                                codomain: Advice.Aux[F, Cod, A])
```

The `domain` value represents the inputs; it is modeled as a list-of-lists since methods can have multiple parameter lists. The `Advice` instances in the list are written in terms of `Eval` to support lazy parameters as well, using `Eval.now` for strict parameters and `Eval.always` for lazy parameters.

The `Dom[_]` type parameter is a typeclass that must exist for every type of input parameter. The specific typeclass is parameterized because what is appropriate will vary depending on how you'll use the woven algebra, but it will typically be something like `cats.Show` to convert things to strings or `io.circe.Encoder` to convert values to JSON.

The `Cod[_]` type parameter is similar to `Dom[_]`, but it must exist for the output type instead of the input types.

This library defines a `ToTraceValue` typeclass which exists to convert things to Natchez's `TraceValue` type. You could also use `cats.Show` for this purpose, but having a separate typeclass specifically for converting to trace attributes lets you customize the behavior for each class. For example, you may want to redact sensitive information, or use a JSON structure that can be parsed by your tracing backend.

Cats Tagless also defines `Trivial` which is always available but doesn't provide any values, which can be useful if you want to weave capturing only inputs but not outputs (for which you'd fix `Cod[_]` to `Trivial`), or vica-versa.

To use `Weave`, define an implicit `Aspect` instance for each algebra to be woven. Often these can be semi-automatically derived:

```scala
object DeepThought {
  implicit val deepThoughtAspect: Aspect[DeepThought, ToTraceValue, ToTraceValue] = Derive.aspect[DeepThought, ToTraceValue, ToTraceValue]
  
  def apply[F[_] : Applicative]: DeepThought[F] = new DeepThought[F] {
    def answer: F[Int] = 42.pure[F]
  }
}
```

Instances of `ToTraceValue` will need to exist for every input type. If you see errors like 

```
On line 5: error: exception during macro expansion:
       scala.reflect.macros.TypecheckException: could not find implicit value for parameter G: com.dwolla.tracing.ToTraceValue[Foo]
        at scala.reflect.macros.contexts.Typers.$anonfun$typecheck$3(Typers.scala:44)
…
```

then implement `ToTraceValue` for the type describe in the error (in this case, `Foo`). You may have to do this several times until instances are available for all the input types.

Once an `Aspect[DeepThought, ToTraceValue, ToTraceValue]` is available, the `traceWithInputs` and `traceWithInputsAndOutputs` extension methods should also be available:

```scala
scala> DeepThought[IO].traceWithInputsAndOutputs
val res8: DeepThought[[+A]cats.effect.IO[A]] = DeepThought$$anon$1$$anon$3@6ad241cf
```
