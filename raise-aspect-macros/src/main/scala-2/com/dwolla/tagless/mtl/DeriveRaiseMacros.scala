/*
 * This file is adapted from cats-tagless:
 *
 *   https://github.com/typelevel/cats-tagless
 *   tag v0.16.5, commit 2f0c9317c09a51784f4f16abb52ee5ae63274e1b
 *   macros/src/main/scala-2/cats/tagless/DeriveMacros.scala
 *
 * Copyright 2019 cats-tagless maintainers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * MODIFICATIONS: the reification and code-generation machinery below is taken
 * from upstream's `DeriveMacros`, reduced to what the `RaiseAspect` derivation
 * needs. `raiseInstrument` (the fused `intercept` generator) and `raiseMapK`
 * transport `cats.mtl.Raise` capability parameters instead of rejecting every
 * method whose signature mentions the effect type. See docs/plans/raise-aspect/
 * for the expansion specification.
 */

package com.dwolla.tagless.mtl

import cats.mtl.{Handle, Raise}
import cats.tagless.aop.Aspect

import scala.reflect.macros.blackbox

class DeriveRaiseMacros(val c: blackbox.Context) {
  import c.internal._
  import c.universe._

  type Transform[T] = PartialFunction[T, T]
  type TransformParam = PartialFunction[Parameter, Tree]

  /** A reified parameter definition. */
  case class Parameter(name: TermName, signature: Type, modifiers: Modifiers) {
    def displayName: String = name.decodedName.toString
  }

  /** A reified method definition with the transformations we need. */
  case class Method(
      name: TermName,
      signature: Type,
      typeParams: List[TypeDef],
      paramLists: List[List[ValDef]],
      returnType: Type,
      body: Tree
  ) {
    def displayName: String = name.decodedName.toString
    def occursInSignature(symbol: Symbol): Boolean = occursIn(signature)(symbol)
    def occursInReturn(symbol: Symbol): Boolean = occursIn(returnType)(symbol)

    /** Construct a new set of parameter lists after substituting some types. */
    def transformedParamLists(types: Transform[Type]): List[List[ValDef]] =
      for (ps <- paramLists)
        yield for (p <- ps) yield {
          val oldType = p.tpt.tpe
          val newType = types.applyOrElse(oldType, identity[Type])
          if (newType == oldType) p else ValDef(p.mods, p.name, TypeTree(newType), p.rhs)
        }

    /** Construct a new set of argument lists based on their name and type. */
    def transformedArgLists(f: TransformParam = PartialFunction.empty): List[List[Tree]] = {
      def id(param: Parameter): Tree = Ident(param.name)

      val f_* : Parameter => Tree = {
        case Parameter(pn, RepeatedParam(pt), pm) =>
          q"${f.andThen(arg => q"for ($pn <- $pn) yield $arg").applyOrElse(Parameter(pn, pt, pm), id)}: _*"
        case Parameter(pn, ByNameParam(pt), pm) =>
          f.applyOrElse(Parameter(pn, pt, pm), id)
        case param =>
          f.applyOrElse(param, id)
      }

      for (ps <- paramLists)
        yield for (p <- ps) yield f_*(Parameter(p.name, p.tpt.tpe, p.mods))
    }

    /** Delegate this method to an existing instance. */
    def delegate(to: Tree, argLists: List[List[Tree]] = transformedArgLists()): Tree = {
      val typeArgs = for (tp <- typeParams) yield typeRef(NoPrefix, tp.symbol, Nil)
      q"$to.$name[..$typeArgs](...$argLists)"
    }

    def definition: Tree = q"override def $name[..$typeParams](...$paramLists): $returnType = $body"
  }

  case class MethodDef(name: String, rhs: Type => Option[Tree])
  object MethodDef {
    def apply(name: String)(rhs: PartialFunction[Type, Tree]): MethodDef = apply(name, rhs.lift)
  }

  final class ParamExtractor(symbol: Symbol) {
    def apply(tpe: Type): Type = appliedType(symbol, tpe)
    def unapply(tpe: Type): Option[Type] = if (tpe.typeSymbol == symbol) Some(tpe.typeArgs.head) else None
  }

  val RepeatedParam = new ParamExtractor(definitions.RepeatedParamClass)
  val ByNameParam = new ParamExtractor(definitions.ByNameParamClass)

  private val RaiseSymbol = symbolOf[Raise[Any, Any]]
  private val HandleSymbol = symbolOf[Handle[Any, Any]]

  /** Returns name of a type, taking care of refinements and anonymous classes. */
  private def typeNameOf(tpe: Type): String = {
    val symbol = tpe.typeSymbol
    val name = symbol.name.decodedName.toString
    if (name != "<refinement>" && !name.contains("$anon")) name
    else symbol.asClass.baseClasses.lift(1).fold(name)(_.name.decodedName.toString)
  }

  def typeConstructorOf(tag: WeakTypeTag[_]): Type =
    tag.tpe.typeConstructor.dealias.etaExpand

  def overridableMembersOf(tpe: Type): Iterable[Symbol] = {
    import definitions._
    val exclude = Set[Symbol](AnyClass, AnyRefClass, AnyValClass, ObjectClass)
    tpe.members.filterNot(m =>
      m.isConstructor || m.isFinal || m.isImplementationArtifact || m.isSynthetic || exclude(m.owner)
    )
  }

  /** Temporarily refresh type parameter names, type-check, restore the names.
    *
    * Avoids type-parameter-shadowing warnings, which matter under
    * `-Xfatal-warnings`. `c.typecheck(silent = true)` does not suppress them.
    */
  def typeCheckWithFreshTypeParams(tree: Tree): Tree = {
    val typeParams = tree.collect { case method: DefDef => method.tparams.map(_.symbol) }.flatten

    val originalNames = for (tp <- typeParams) yield {
      val original = tp.name.toTypeName
      setName(tp, c.freshName(TypeName(original.toString)))
      original
    }

    val typed = c.typecheck(tree)
    for ((tp, original) <- typeParams.zip(originalNames)) setName(tp, original)
    typed
  }

  def abort(message: String): Nothing = c.abort(c.enclosingPosition, message)

  /** `tpe.contains` is broken before Scala 2.13. See scala/scala#6122. */
  def occursIn(tpe: Type)(symbol: Symbol): Boolean = tpe.exists(_.typeSymbol == symbol)

  def hasFlag(symbol: Symbol)(flag: FlagSet): Boolean = {
    val flagSet = flags(symbol)
    (flagSet | flag) == flagSet
  }

  private def typeParamsOf(signature: Type) = for (t <- signature.typeParams) yield typeDef(t)
  private def typeArgsFrom(signature: Type) = for (t <- signature.typeParams) yield typeRef(NoPrefix, t, Nil)

  def delegateTypes(algebra: Type, members: Iterable[Symbol])(rhs: (TypeSymbol, List[Type]) => Type): Iterable[Tree] =
    for (member <- members if member.isType) yield {
      val tpe = member.asType
      val signature = tpe.typeSignatureIn(algebra)
      q"type ${tpe.name}[..${typeParamsOf(signature)}] = ${rhs(tpe, typeArgsFrom(signature))}"
    }

  def delegateAbstractTypes(algebra: Type, members: Iterable[Symbol], instance: Type): Iterable[Tree] =
    delegateTypes(algebra, members.filter(_.isAbstract))(typeRef(instance, _, _))

  def delegateMethods(algebra: Type, members: Iterable[Symbol], instance: Symbol)(
      transform: Transform[Method]
  ): Iterable[Tree] = for (member <- members if member.isMethod && !member.asMethod.isAccessor) yield {
    val name = member.name.toTermName
    val signature = member.typeSignatureIn(algebra)
    val paramLists =
      for (ps <- signature.paramLists)
        yield for (p <- ps) yield {
          // Only preserve the by-name and implicit modifiers (e.g. drop the default parameter flag).
          val flagList = List(Flag.BYNAMEPARAM, Flag.IMPLICIT).filter(hasFlag(p))
          val modifiers = Modifiers(flagList.foldLeft(Flag.PARAM)(_ | _))
          ValDef(modifiers, p.name.toTermName, TypeTree(p.typeSignatureIn(algebra)), EmptyTree)
        }

    val argLists =
      for (ps <- signature.paramLists)
        yield for (p <- ps) yield p.typeSignatureIn(algebra) match {
          case RepeatedParam(_) => q"${p.name.toTermName}: _*"
          case _ => Ident(p.name)
        }

    val body = q"$instance.$name[..${typeArgsFrom(signature)}](...$argLists)"
    val reified = Method(name, signature, typeParamsOf(signature), paramLists, signature.finalResultType, body)
    transform.applyOrElse(reified, identity[Method]).definition
  }

  /** Type-check a definition of type `instance` with stubbed methods to gain more type information. */
  def declare(instance: Type): Tree = {
    val members = overridableMembersOf(instance).filter(_.isAbstract)
    val stubs = delegateMethods(instance, members, NoSymbol) { case m => m.copy(body = q"_root_.scala.Predef.???") }
    typeCheckWithFreshTypeParams(q"new $instance { ..$stubs }") match {
      case Block(List(declaration), _) => declaration
      case other => abort(s"Could not declare $instance; unexpected tree shape $other")
    }
  }

  /** Implement a possibly refined `algebra` with the provided `members`. */
  def implement(algebra: Type)(typeArgs: Symbol*)(members: Iterable[Tree]): Tree = {
    // If `members.isEmpty` we need an extra statement to ensure the generation of an anonymous class.
    val nonEmptyMembers = if (members.isEmpty) q"()" :: Nil else members
    val applied = appliedType(algebra, typeArgs.toList.map(_.asType.toTypeConstructor))
    applied match {
      case RefinedType(parents, scope) =>
        val refinements = delegateTypes(applied, scope.filterNot(_.isAbstract)) { (tpe, _) =>
          tpe.typeSignatureIn(applied).resultType
        }
        q"new ..$parents { ..$refinements; ..$nonEmptyMembers }"
      case _ =>
        q"new $applied { ..$nonEmptyMembers }"
    }
  }

  /** Create a new instance of `typeClass` for `algebra`. */
  def instantiate[T: WeakTypeTag](tag: WeakTypeTag[_], typeArgs: Type*)(methods: (Type => MethodDef)*): Tree = {
    val algebra = typeConstructorOf(tag)
    val Ta = appliedType(symbolOf[T], algebra :: typeArgs.toList)
    val rhsMap = methods.iterator.map(_.apply(algebra)).flatMap(MethodDef.unapply).toMap
    val declaration = declare(Ta)
    val (parents, self, members) = declaration match {
      case ClassDef(_, _, _, Template(ps, s, ms)) => (ps, s, ms)
      case other => abort(s"Could not implement $Ta; unexpected tree shape $other")
    }

    val implementations = members.map {
      case member: DefDef =>
        val method = member.symbol.asMethod
        val impl = for {
          rhsOf <- rhsMap.get(method.name.toString)
          rhs <- rhsOf(method.typeSignatureIn(Ta))
        } yield defDef(method, rhs)
        impl.getOrElse(member)
      case member => member
    }

    val definition = classDef(declaration.symbol, Template(parents, self, implementations))
    typeCheckWithFreshTypeParams(q"{ $definition; new ${declaration.symbol} }")
  }

  // ------------------------------------------------------------------------
  // RaiseAspect-specific derivation
  // ------------------------------------------------------------------------

  /** The error type `e` iff `tpe` dealiases to exactly `Raise[F, e]`, where `F` is
    * the algebra's effect symbol and `e` does not mention it.
    *
    * The symbol comparison must be exact rather than a subtype test:
    * `Handle[F, E] extends Raise[F, E]`, and `Handle` consumes `F`, so treating a
    * `Handle` parameter as transportable would generate unsound code.
    */
  private def capabilityError(tpe: Type, f: Symbol): Option[Type] = {
    val dealiased = tpe.dealias
    if (dealiased.typeSymbol != RaiseSymbol) None
    else
      dealiased.typeArgs match {
        case List(effect, error) if effect.typeSymbol == f && !occursIn(error)(f) => Some(error)
        case _ => None
      }
  }

  private def isHandle(tpe: Type, f: Symbol): Option[Type] = {
    val dealiased = tpe.dealias
    if (dealiased.typeSymbol != HandleSymbol) None
    else
      dealiased.typeArgs match {
        case List(effect, error) if effect.typeSymbol == f => Some(error)
        case _ => None
      }
  }

  /** Does this method return `F[A]` with `F` appearing nowhere else in the return
    * type? `F[F[A]]` has `F` as its type symbol too, so checking the symbol alone
    * would let a nested effect through.
    */
  private def returnsEffectDirectly(method: Method, f: Symbol): Boolean =
    method.returnType.typeSymbol == f && !method.returnType.typeArgs.exists(occursIn(_)(f))

  /** Strip by-name and repeated wrappers. */
  private def bareType(tpe: Type): Type = tpe match {
    case ByNameParam(t) => t
    case RepeatedParam(t) => t
    case t => t
  }

  /** The generated method's own implicit parameters.
    *
    * Scala 2's grammar permits at most one implicit clause and requires it to be
    * last, so the trailing clause is the only place to look — the same rule
    * [[domainOf]] relies on when it drops that clause from the domain.
    */
  private def implicitParamsOf(method: Method): List[ValDef] =
    method.paramLists.lastOption.toList.flatten.filter(_.mods.hasFlag(Flag.IMPLICIT))

  /** Exactly one of the generated method's own implicit parameters whose declared
    * type conforms to `tpe`, if there is one.
    *
    * This is a subtype test against what the method is ''handed'', not an implicit
    * search: nothing is derived, no companion scope is consulted, and nothing
    * chains. `<:<` rather than `=:=` because a wider contravariant instance can
    * legitimately stand in for the narrower one the derivation asked for.
    *
    * Two conforming parameters abort rather than silently taking the first: real
    * implicit search would report the ambiguity, and an arbitrary pick surfaces
    * much later as a baffling wrong-instance bug.
    */
  private def methodLocalInstance(tpe: Type, describe: => String, method: Method): Option[Tree] =
    implicitParamsOf(method).filter(p => bareType(p.tpt.tpe) <:< tpe) match {
      case Nil => None
      case p :: Nil => Some(Ident(p.name))
      case ps =>
        abort(
          s"ambiguous method-local implicits for $tpe $describe: " +
            ps.map(_.name.decodedName.toString).mkString(", ")
        )
    }

  /** A parameter the user most likely meant to make implicit: its declared type
    * would have satisfied the instance we could not find, but it sits in an
    * ordinary clause, where nothing can reach it. Without this the message names
    * only the parameter being advised and gives no hint that a conforming
    * instance was sitting unused in the argument list.
    */
  private def unusableCandidateHint(tpe: Type, method: Method): Option[String] =
    method.paramLists.flatten
      .filterNot(_.mods.hasFlag(Flag.IMPLICIT))
      .find(p => bareType(p.tpt.tpe) <:< tpe)
      .map { p =>
        s"Parameter ${p.name.decodedName} of method ${method.displayName} would conform, but only the " +
          "method's implicit parameters are considered as method-local instances."
      }

  /** Resolve an instance for `tpe` at the derivation site, falling back to the
    * generated method's own implicit parameters, and only then aborting.
    */
  private def inferOrAbort(tpe: Type, describe: => String, method: Method): Tree =
    c.inferImplicitValue(tpe) match {
      case EmptyTree =>
        methodLocalInstance(tpe, describe, method).getOrElse(
          abort(s"Not found: implicit $tpe $describe" + unusableCandidateHint(tpe, method).fold("")(". " + _))
        )
      case tree => tree
    }

  /** The `Err[E]` for a capability parameter's error type, or an abort naming
    * both. Distinct from `inferOrAbort` so the message can say "error type"
    * rather than "parameter" — the parameter is the `Raise`, not the error.
    */
  private def inferErrOrAbort(Err: Type, errorType: Type, method: Method): Tree = {
    val tpe = appliedType(Err, errorType)
    c.inferImplicitValue(tpe) match {
      case EmptyTree =>
        methodLocalInstance(tpe, s"for the error type $errorType raised by method ${method.displayName}", method)
          .getOrElse(
            abort(
              s"no evidence for the error type $errorType raised by method ${method.displayName}: " +
                s"an implicit ${Err.typeSymbol.name}[$errorType] is required at the derivation site. " +
                s"Supply one, or derive at Err = cats.tagless.Trivial to opt out of error evidence." +
                unusableCandidateHint(tpe, method).fold("")(" " + _)
            )
          )
      case tree => tree
    }
  }

  /** Reject every parameter that mentions `F` other than as a capability. */
  private def validateParams(method: Method, f: Symbol): Unit =
    for (ps <- method.paramLists; p <- ps) {
      val bare = bareType(p.tpt.tpe)
      val paramName = p.name.decodedName.toString
      if (capabilityError(bare, f).isEmpty) {
        isHandle(bare, f) match {
          case Some(error) =>
            abort(
              s"method ${method.displayName} takes cats.mtl.Handle[F, $error]; Handle consumes F and cannot be " +
                s"woven. Take Raise[F, $error] in algebra methods and introduce Handle at the boundary " +
                "(Handle.allow / rescue)."
            )
          case None =>
            if (occursIn(bare)(f))
              abort(
                s"parameter $paramName of method ${method.displayName} mentions the effect type F in an " +
                  "unsupported position; RaiseAspect supports F only as the top-level return type and in " +
                  "Raise[F, E] parameters."
              )
        }
      }
    }

  /** The `domain` value: `Advice`s for every non-capability parameter.
    *
    * Follows upstream by dropping the trailing implicit clause wholesale, then
    * additionally filters out capability parameters so that a non-implicit
    * `Raise[F, E]` is excluded too. Keeping upstream's rule is what makes law L9
    * (conservative extension) hold for every capability-free algebra.
    */
  private def domainOf(method: Method, f: Symbol, Dom: Type): List[List[Tree]] = {
    val AspectAdvice = reify(Aspect.Advice)
    val hasImplicits = method.signature.paramLists.lastOption.flatMap(_.headOption).exists(_.isImplicit)
    val clauses = if (hasImplicits) method.paramLists.dropRight(1) else method.paramLists

    for (ps <- clauses)
      yield for (p <- ps if capabilityError(bareType(p.tpt.tpe), f).isEmpty) yield {
        val pt = bareType(p.tpt.tpe)
        val displayName = p.name.decodedName.toString
        val instance = inferOrAbort(
          appliedType(Dom, pt),
          s"for parameter $displayName of method ${method.displayName}",
          method
        )
        val constructor = TermName(if (p.mods.hasFlag(Flag.BYNAMEPARAM)) "byName" else "byValue")
        val advice = q"$AspectAdvice.$constructor[$Dom, $pt]($displayName, ${p.name})($instance)"
        p.tpt.tpe match {
          case RepeatedParam(_) => q"${q"for (${p.name} <- ${p.name}) yield $advice"}: _*"
          case _ => advice
        }
      }
  }

  /** Substitute the effect inside capability parameter types, leaving the error
    * type exactly as declared (`Raise` is contravariant in `E`; reconstructing the
    * error type risks variance drift).
    *
    * `raiseInstrument` no longer needs this — the capability is decorated at the
    * ''same'' carrier — but `raiseMapK` still retypes `Raise[F, E]` to
    * `Raise[G, E]` for the genuine carrier change `mapK` performs, so this stays.
    */
  private def substituteCapabilities(method: Method, f: Symbol, newEffect: Type): List[List[ValDef]] =
    method.transformedParamLists {
      case tpe if capabilityError(tpe, f).isDefined =>
        appliedType(RaiseSymbol, newEffect :: capabilityError(tpe, f).toList)
    }

  // def intercept[F[_]](af: Alg[F])(fk: Aspect.Weave[F, Dom, Cod, *] ~> F, onRaise: OnRaise[F, Err])
  //                     (implicit F: Apply[F]): Alg[F]
  def raiseInstrument(Dom: Type, Cod: Type, Err: Type)(algebra: Type): MethodDef = MethodDef("intercept") {
    case PolyType(List(f), MethodType(List(af), MethodType(List(fk, onRaise), MethodType(List(applyF), _)))) =>
      val F = f.asType.toTypeConstructor
      val Af = singleType(NoPrefix, af)
      val members = overridableMembersOf(Af)
      val types = delegateAbstractTypes(Af, members, Af)
      val algebraName = typeNameOf(algebra)

      val methods = delegateMethods(Af, members, af) {
        case method if returnsEffectDirectly(method, f) =>
          validateParams(method, f)

          val AspectAdvice = reify(Aspect.Advice)
          val RaiseAspectRef = reify(RaiseAspect)
          val typeArgs = method.returnType.typeArgs

          // The capability is decorated *in place*: same carrier, same declared
          // type, so there is no parameter-type substitution to do and the
          // generated method's parameter lists are the algebra's own.
          val args = method.transformedArgLists { case Parameter(pn, pt, _) if capabilityError(pt, f).isDefined =>
            val errorType = capabilityError(pt, f).get
            val errInstance = inferErrOrAbort(Err, errorType, method)
            q"$RaiseAspectRef.observing[$F, $errorType, $Err]($pn, $onRaise)($applyF, $errInstance)"
          }

          val codInstance = inferOrAbort(
            appliedType(Cod, typeArgs),
            s"for the result of method ${method.displayName}",
            method
          )
          val codomain =
            q"$AspectAdvice[$F, $Cod, ..$typeArgs](${method.displayName}, ${method.delegate(Ident(af), args)})($codInstance)"
          val weave =
            q"${reify(Aspect.Weave)}[$F, $Dom, $Cod, ..$typeArgs]($algebraName, ${domainOf(method, f, Dom)}, $codomain)"

          method.copy(body = q"$fk.apply[..$typeArgs]($weave)")
        case method if method.occursInReturn(f) =>
          abort(
            s"method ${method.displayName} returns ${method.returnType}; RaiseAspect supports F only as the " +
              "top-level return type, not nested inside another type."
          )
        case method if method.occursInSignature(f) =>
          abort(
            s"method ${method.displayName} mentions the effect type F but does not return F[?]; RaiseAspect " +
              "supports F only as the top-level return type and in Raise[F, E] parameters."
          )
      }

      implement(algebra)(f)(types ++ methods)
  }

  // def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G]
  def raiseMapK(Err: Type)(algebra: Type): MethodDef = MethodDef("mapK") {
    case PolyType(List(f, g), MethodType(List(af), MethodType(List(arrow), _))) =>
      val G = g.asType.toTypeConstructor
      val Af = singleType(NoPrefix, af)
      val members = overridableMembersOf(Af)
      val types = delegateAbstractTypes(Af, members, Af)

      val methods = delegateMethods(Af, members, af) {
        case method if returnsEffectDirectly(method, f) =>
          validateParams(method, f)

          val args = method.transformedArgLists { case Parameter(pn, pt, _) if capabilityError(pt, f).isDefined =>
            val errorType = capabilityError(pt, f).get
            val errInstance = inferErrOrAbort(Err, errorType, method)
            q"$arrow.pull($pn)($errInstance)"
          }

          method.copy(
            paramLists = substituteCapabilities(method, f, G),
            body = q"$arrow.fk(${method.delegate(Ident(af), args)})",
            returnType = appliedType(G, method.returnType.typeArgs)
          )
        case method if method.occursInReturn(f) =>
          abort(
            s"method ${method.displayName} returns ${method.returnType}; RaiseFunctorK supports F only as the " +
              "top-level return type, not nested inside another type."
          )
        case method if method.occursInSignature(f) =>
          abort(
            s"method ${method.displayName} mentions the effect type F but does not return F[?]; RaiseFunctorK " +
              "supports F only as the top-level return type and in Raise[F, E] parameters."
          )
      }

      implement(algebra)(g)(types ++ methods)
  }

  def aspect[Alg[_[_]], Dom[_], Cod[_], Err[_]](implicit
      tag: WeakTypeTag[Alg[Any]],
      dom: WeakTypeTag[Dom[Any]],
      cod: WeakTypeTag[Cod[Any]],
      err: WeakTypeTag[Err[Any]]
  ): Tree = {
    val Dom = typeConstructorOf(dom)
    val Cod = typeConstructorOf(cod)
    val Err = typeConstructorOf(err)
    instantiate[RaiseAspect[Alg, Dom, Cod, Err]](tag, Dom, Cod, Err)(
      raiseInstrument(Dom, Cod, Err),
      raiseMapK(Err)
    )
  }

  def functorK[Alg[_[_]], Err[_]](implicit
      tag: WeakTypeTag[Alg[Any]],
      err: WeakTypeTag[Err[Any]]
  ): Tree = {
    val Err = typeConstructorOf(err)
    instantiate[RaiseFunctorK[Alg, Err]](tag, Err)(raiseMapK(Err))
  }
}
