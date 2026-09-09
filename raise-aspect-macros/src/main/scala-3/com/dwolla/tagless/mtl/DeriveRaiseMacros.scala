/*
 * This file is adapted from cats-tagless:
 *
 *   https://github.com/typelevel/cats-tagless
 *   tag v0.16.5, commit 2f0c9317c09a51784f4f16abb52ee5ae63274e1b
 *   core/src/main/scala-3/cats/tagless/macros/DeriveMacros.scala
 *   core/src/main/scala-3/cats/tagless/macros/MacroAspect.scala
 *   core/src/main/scala-3/cats/tagless/macros/MacroFunctorK.scala
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
 * MODIFICATIONS: the reflection machinery is upstream's `DeriveMacros`, reduced
 * to the subset the `RaiseAspect` derivation needs. `deriveIntercept` (the
 * fused `intercept` generator, rewritten from `MacroAspect`) and `deriveMapK`
 * (rewritten from `MacroFunctorK`) transport `cats.mtl.Raise` capability
 * parameters instead of rejecting methods whose signatures mention the effect
 * type. Upstream's `addToGivenScope` block is deliberately omitted.
 */

package com.dwolla.tagless.mtl

import cats.arrow.FunctionK
import cats.mtl.{Handle, Raise}
import cats.tagless.aop.Aspect
import cats.{Eval, FlatMap}

import scala.annotation.experimental
import scala.quoted.*

private object DeriveRaiseMacros:
  // Unfortunately there is no flag for default parameters.
  private val defaultRegex = ".*\\$default\\$\\d+".r

@experimental
private class DeriveRaiseMacros[Q <: Quotes](using val q: Q):
  import quotes.reflect.*

  type Transform = PartialFunction[(Symbol, TypeRepr, Term), Term]

  private val nonOverridableOwners =
    TypeRepr.of[(Object, Any, AnyRef, AnyVal)].typeArgs.map(_.typeSymbol).toSet

  private val nonOverridableFlags =
    List(Flags.Final, Flags.Artifact, Flags.Synthetic, Flags.Mutable, Flags.Param)

  // TODO: This is a hack - replace with `Symbol.newTypeAlias` on Scala 3.6+.
  // Retained (unlike upstream's given-scope hack) because `overridableMembers`
  // cannot build a class for any algebra with a type member without it, and it
  // fails loudly rather than silently.
  private def newTypeAlias(owner: Symbol, name: String, flags: Flags, tpe: TypeRepr, privateIn: Symbol): Symbol =
    try
      val ctx = q.getClass.getMethod("ctx").invoke(q)
      val aliasClass = Class.forName("dotty.tools.dotc.core.Types$TypeAlias")
      val symbolsClass = Class.forName("dotty.tools.dotc.core.Symbols$")
      val decoratorsClass = Class.forName("dotty.tools.dotc.core.Decorators$")
      val alias = aliasClass.getConstructors.head.newInstance(tpe)
      val symbols = symbolsClass.getDeclaredField("MODULE$").get(null)
      val decorators = decoratorsClass.getDeclaredField("MODULE$").get(null)
      val toTypeName = decorators.getClass.getMethods.find(_.getName == "toTypeName").get
      val newSymbol = symbols.getClass.getMethods.find(_.getName == "newSymbol").get
      val typeName = toTypeName.invoke(decorators, name)
      val nestingLevel = ctx.getClass.getMethod("nestingLevel").invoke(ctx)
      val sym = newSymbol.invoke(symbols, ctx, owner, typeName, flags, alias, privateIn, 0, nestingLevel)
      sym.asInstanceOf[Symbol]
    catch
      case _: Exception =>
        report.errorAndAbort(s"Not supported: type $name in $owner")

  extension (xf: Transform)
    def transformRepeated(method: Symbol, tpe: TypeRepr, arg: Term): Tree =
      val x = Symbol.freshName("x")
      val resultType = xf(method, tpe, Select.unique(arg, "head")).tpe
      val lambdaType = MethodType(x :: Nil)(_ => tpe :: Nil, _ => resultType)
      val lambda = Lambda(method, lambdaType, (_, xs) => xf(method, tpe, xs.head.asExpr.asTerm))
      val result = Select.overloaded(arg, "map", resultType :: Nil, lambda :: Nil)
      val repeatedType = defn.RepeatedParamClass.typeRef.appliedTo(resultType)
      Typed(result, TypeTree.of(using repeatedType.asType))

    def transformArg(method: Symbol, paramAndArg: (Definition, Tree)): Tree =
      paramAndArg match
        case (param: ValDef, arg: Term) =>
          val paramType = param.tpt.tpe.widenParam
          if !xf.isDefinedAt(method, paramType, arg) then arg
          else if !param.tpt.tpe.isRepeated then xf(method, paramType, arg)
          else xf.transformRepeated(method, paramType, arg)
        case (_, arg) => arg

  extension (term: Term)
    def appliedToAll(argss: List[List[Tree]]): Term =
      argss.foldLeft(term): (term, args) =>
        val typeArgs = for case arg: TypeTree <- args yield arg
        val termArgs = for case arg: Term <- args yield arg
        if typeArgs.isEmpty then term.appliedToArgs(termArgs)
        else term.appliedToTypeTrees(typeArgs)

    def call(method: Symbol)(argss: List[List[Tree]]): Term = argss match
      case args1 :: args2 :: argss if !args1.exists(_.isExpr) && args2.forall(_.isExpr) =>
        val typeArgs = for case arg: TypeTree <- args1 yield arg.tpe
        val termArgs = for case arg: Term <- args2 yield arg
        Select.overloaded(term, method.name, typeArgs, termArgs).appliedToAll(argss)
      case args :: argss if !args.exists(_.isExpr) =>
        val typeArgs = for case arg: TypeTree <- args yield arg.tpe
        Select.overloaded(term, method.name, typeArgs, Nil).appliedToAll(argss)
      case args :: argss if args.forall(_.isExpr) =>
        val termArgs = for case arg: Term <- args yield arg
        Select.overloaded(term, method.name, Nil, termArgs).appliedToAll(argss)
      case argss =>
        term.select(method).appliedToAll(argss)

  extension (expr: Expr[?])
    def transformTo[A: Type](
        args: Transform = PartialFunction.empty,
        body: Transform = PartialFunction.empty
    ): Expr[A] =
      val term = expr.asTerm

      def transformDef(method: DefDef)(argss: List[List[Tree]]): Option[Term] =
        val sym = method.symbol
        val delegate = term.call(sym):
          for (params, xs) <- method.paramss.zip(argss)
          yield
            for paramAndArg <- params.params.zip(xs)
            yield args.transformArg(sym, paramAndArg)
        Some(body.applyOrElse((sym, method.returnTpt.tpe, delegate), _ => delegate))

      def transformVal(value: ValDef): Option[Term] =
        val sym = value.symbol
        val delegate = term.select(sym)
        Some(body.applyOrElse((sym, value.tpt.tpe, delegate), _ => delegate))

      Some(term).newClassOf[A](transformDef, transformVal)

  extension (sym: Symbol)
    def privateIn: Symbol =
      sym.privateWithin.fold(Symbol.noSymbol)(_.typeSymbol)

    def overrideKeeping(flags: Flags*): Flags =
      flags.iterator.filter(sym.flags.is).foldLeft(Flags.Override)(_ | _)

    def overridableMembers(delegate: Option[Term]): List[Symbol] =
      val typeAliases = for
        member <- sym.typeMembers
        if member.isTypeDef
        tpe = delegate match
          case Some(delegate) => TypeSelect(delegate, member.name).tpe
          case None => report.errorAndAbort(s"Not supported: $member in $sym")
      yield member -> tpe

      val cls = This(sym).tpe
      val aliases = typeAliases.toMap
      val (from, to) = typeAliases.unzip

      for
        member <- List.concat(sym.typeMembers, sym.fieldMembers, sym.methodMembers)
        if !member.isNoSymbol
        if !member.isClassConstructor
        if !nonOverridableFlags.exists(member.flags.is)
        if !nonOverridableOwners.contains(member.owner)
        if !DeriveRaiseMacros.defaultRegex.matches(member.name)
      yield
        if member.isTypeDef then
          val flags = member.overrideKeeping(Flags.Infix)
          newTypeAlias(sym, member.name, flags, aliases(member), member.privateIn)
        else if member.isValDef then
          val tpe = cls.memberType(member).substituteTypes(from, to)
          val flags = member.overrideKeeping(Flags.Lazy)
          Symbol.newVal(sym, member.name, tpe, flags, member.privateIn)
        else if member.isDefDef then
          val tpe = cls.memberType(member).substituteTypes(from, to)
          val flags = member.overrideKeeping(Flags.ExtensionMethod, Flags.Infix)
          Symbol.newMethod(sym, member.name, tpe, flags, member.privateIn)
        else member

  extension (tpe: TypeRepr)
    def contains(that: TypeRepr): Boolean =
      tpe != tpe.substituteTypes(that.typeSymbol :: Nil, TypeRepr.of[Any] :: Nil)

    def isRepeated: Boolean =
      tpe.typeSymbol == defn.RepeatedParamClass

    def isByName: Boolean = tpe match
      case ByNameType(_) => true
      case _ => false

    def widenParam: TypeRepr =
      if tpe.isRepeated then tpe.typeArgs.head else tpe.widenByName

  extension (delegate: Option[Term])
    def newClassOf[T: Type](
        transformDef: DefDef => List[List[Tree]] => Option[Term],
        transformVal: ValDef => Option[Term]
    ): Expr[T] =
      val T = TypeRepr.of[T].dealias.typeSymbol
      if T.flags.is(Flags.Enum) then report.errorAndAbort(s"Not supported: $T is an enum")
      if !T.isClassDef || !T.flags.is(Flags.Trait) && !T.flags.is(Flags.Abstract) then
        report.errorAndAbort(s"Not supported: $T is not a trait or abstract class")

      val name = Symbol.freshName("$anon")
      val parents = List(TypeTree.of[Object], TypeTree.of[T])
      val cls = Symbol.newClass(Symbol.spliceOwner, name, parents.map(_.tpe), _.overridableMembers(delegate), None)
      val members = cls.declarations
        .filterNot(_.isClassConstructor)
        .map: member =>
          member.tree match
            case method: DefDef => DefDef(member, transformDef(method))
            case value: ValDef => ValDef(member, transformVal(value))
            case tpe: TypeDef => tpe
            case _ => report.errorAndAbort(s"Not supported: $member in ${member.owner}")

      val newCls = New(TypeIdent(cls)).select(cls.primaryConstructor).appliedToNone
      Block(ClassDef(cls, parents, members) :: Nil, newCls).asExprOf[T]

  // ----------------------------------------------------------------------
  // RaiseAspect-specific helpers
  // ----------------------------------------------------------------------

  /** The error type `e` iff `tpe` dealiases to exactly `Raise[carrier, e]`.
    *
    * The symbol comparison is exact rather than a subtype test: `Handle[F, E]`
    * extends `Raise[F, E]` and `Handle` consumes `F`, so treating a `Handle`
    * parameter as transportable would generate unsound code.
    */
  def capabilityError(tpe: TypeRepr, carrier: TypeRepr): Option[TypeRepr] =
    val dealiased = tpe.dealias
    if dealiased.typeSymbol != TypeRepr.of[Raise].typeSymbol then None
    else
      dealiased.typeArgs match
        case List(effect, error) if effect =:= carrier && !error.contains(carrier) => Some(error)
        case _ => None

  def handleError(tpe: TypeRepr, carrier: TypeRepr): Option[TypeRepr] =
    val dealiased = tpe.dealias
    if dealiased.typeSymbol != TypeRepr.of[Handle].typeSymbol then None
    else
      dealiased.typeArgs match
        case List(effect, error) if effect =:= carrier => Some(error)
        case _ => None

  def isContextFunction(tpe: TypeRepr): Boolean =
    tpe.dealias match
      case AppliedType(tycon, _) => tycon.typeSymbol.name.startsWith("ContextFunction")
      case _ => false

  /** Decompose a method type into its parameter clauses and result type. */
  def clausesOf(tpe: TypeRepr): (List[List[(String, TypeRepr)]], TypeRepr) = tpe match
    case PolyType(_, _, res) => clausesOf(res)
    case MethodType(names, types, res) =>
      val (rest, result) = clausesOf(res)
      (names.zip(types) :: rest, result)
    case other => (Nil, other)

  private def paramsOf(method: Symbol)(select: TermParamClause => Boolean): List[ValDef] =
    method.tree match
      case d: DefDef => d.termParamss.filter(select).flatMap(_.params)
      case _ => Nil

  /** Exactly one of the generated method's own given/implicit parameters whose
    * declared type conforms to `tpe`, if there is one.
    *
    * This is a subtype test against what the method is ''handed'', not an implicit
    * search: nothing is derived, no companion scope is consulted, and nothing
    * chains. `<:<` rather than `=:=` because a wider contravariant instance can
    * legitimately stand in for the narrower one the derivation asked for.
    *
    * Every using clause is searched, not only the last: Scala 3 permits several,
    * in any position. Two conforming parameters abort rather than silently taking
    * the first — real implicit search would report the ambiguity, and an arbitrary
    * pick surfaces much later as a baffling wrong-instance bug.
    */
  private def methodLocalInstance(tpe: TypeRepr, describe: => String, method: Symbol): Option[Term] =
    paramsOf(method)(c => c.isGiven || c.isImplicit).filter(_.tpt.tpe.widenParam <:< tpe) match
      case Nil => None
      case p :: Nil => Some(Ref(p.symbol))
      case ps =>
        report.errorAndAbort(
          s"ambiguous method-local givens for ${tpe.show} $describe: ${ps.map(_.name).mkString(", ")}"
        )

  /** A parameter the user most likely meant to make a given: its declared type
    * would have satisfied the instance we could not find, but it sits in an
    * ordinary clause, where nothing can reach it. Without this the message names
    * only the parameter being advised and gives no hint that a conforming
    * instance was sitting unused in the argument list.
    */
  private def unusableCandidateHint(tpe: TypeRepr, method: Symbol): Option[String] =
    paramsOf(method)(c => !c.isGiven && !c.isImplicit)
      .find(_.tpt.tpe.widenParam <:< tpe)
      .map { p =>
        s"Parameter ${p.name} of method ${method.name} would conform, but only the method's using/implicit " +
          "parameters are considered as method-local instances."
      }

  /** Resolve an instance for `tpe` at the derivation site, falling back to the
    * generated method's own given/implicit parameters, and only then aborting.
    */
  def summonOrAbort(tpe: TypeRepr, describe: => String, method: Symbol): Term =
    Implicits.search(tpe) match
      case success: ImplicitSearchSuccess => success.tree
      case _ =>
        methodLocalInstance(tpe, describe, method).getOrElse(
          report.errorAndAbort(
            s"Not found: given ${tpe.show} $describe" + unusableCandidateHint(tpe, method).fold("")(". " + _)
          )
        )

  /** As [[summonOrAbort]], but worded for a capability parameter's error type.
    * Kept textually identical to the Scala 2 axis's message only for this
    * method's own base wording — the two axes are held to behavioral
    * agreement there. The appended non-implicit-parameter hint and the
    * ambiguity message (thrown from [[methodLocalInstance]]) use this axis's
    * "using/implicit"/"givens" vocabulary where Scala 2 says
    * "implicit"/"implicits".
    */
  def summonErrOrAbort(Err: TypeRepr, errorType: TypeRepr, method: Symbol): Term =
    val tpe = Err.appliedTo(errorType)
    Implicits.search(tpe) match
      case success: ImplicitSearchSuccess => success.tree
      case _ =>
        methodLocalInstance(tpe, s"for the error type ${errorType.show} raised by method ${method.name}", method)
          .getOrElse(
            report.errorAndAbort(
              s"no evidence for the error type ${errorType.show} raised by method ${method.name}: " +
                s"an implicit ${Err.typeSymbol.name}[${errorType.show}] is required at the derivation site. " +
                s"Supply one, or derive at Err = cats.tagless.Trivial to opt out of error evidence." +
                unusableCandidateHint(tpe, method).fold("")(" " + _)
            )
          )

  /** Reject every algebra member that uses the effect type in a way the
    * derivation cannot support. Runs against the ''declared'' algebra `Alg[F]`,
    * before any class synthesis, so the messages name the types the user wrote
    * and the errors arrive before a confusing synthesis failure.
    */
  def validate(algebra: TypeRepr, effect: TypeRepr, typeClassName: String): Unit =
    for
      member <- algebra.typeSymbol.methodMembers
      if !member.isNoSymbol
      if !member.isClassConstructor
      if !nonOverridableOwners.contains(member.owner)
      if !DeriveRaiseMacros.defaultRegex.matches(member.name)
      memberType = algebra.memberType(member)
      if memberType.contains(effect)
    do
      val (clauses, result) = clausesOf(memberType)
      val returnsEffectDirectly =
        result.typeSymbol == effect.typeSymbol && !result.typeArgs.exists(_.contains(effect))

      if isContextFunction(result) then
        report.errorAndAbort(
          s"method ${member.name} returns a context function type; RaiseAspect cannot weave one. " +
            s"Take the capability as a using parameter instead, e.g. (using R: Raise[F, E]): F[A]."
        )

      for (paramName, paramType) <- clauses.flatten do
        val bare = paramType.widenParam
        if capabilityError(bare, effect).isEmpty then
          handleError(bare, effect) match
            case Some(error) =>
              report.errorAndAbort(
                s"method ${member.name} takes cats.mtl.Handle[F, ${error.show}]; Handle consumes F and cannot be " +
                  s"woven. Take Raise[F, ${error.show}] in algebra methods and introduce Handle at the boundary " +
                  "(Handle.allow / rescue)."
              )
            case None =>
              if bare.contains(effect) then
                report.errorAndAbort(
                  s"parameter $paramName of method ${member.name} mentions the effect type F in an unsupported " +
                    s"position; $typeClassName supports F only as the top-level return type and in Raise[F, E] " +
                    "parameters."
                )

      if !returnsEffectDirectly then
        if result.contains(effect) then
          report.errorAndAbort(
            s"method ${member.name} returns ${result.show}; $typeClassName supports F only as the top-level " +
              "return type, not nested inside another type."
          )
        else
          report.errorAndAbort(
            s"method ${member.name} mentions the effect type F but does not return F[?]; $typeClassName " +
              "supports F only as the top-level return type and in Raise[F, E] parameters."
          )

end DeriveRaiseMacros

@experimental
private[mtl] object RaiseAspectMacros:

  def aspect[Alg[_[_]]: Type, Dom[_]: Type, Cod[_]: Type, Err[_]: Type](using Quotes)
      : Expr[RaiseAspect[Alg, Dom, Cod, Err]] = '{
    new RaiseAspect[Alg, Dom, Cod, Err]:
      def intercept[F[_]](af: Alg[F])(
          fk: FunctionK[[X] =>> Aspect.Weave[F, Dom, Cod, X], F],
          onRaise: OnRaise[F, Err]
      )(implicit F: FlatMap[F]): Alg[F] =
        ${ deriveIntercept[Alg, Dom, Cod, Err, F]('af, 'fk, 'onRaise, 'F) }

      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G] =
        ${ deriveMapK[Alg, F, G, Err]('af, 'arrow) }
  }

  def functorK[Alg[_[_]]: Type, Err[_]: Type](using Quotes): Expr[RaiseFunctorK[Alg, Err]] = '{
    new RaiseFunctorK[Alg, Err]:
      def mapK[F[_], G[_]](af: Alg[F])(arrow: RaiseArrow[F, G, Err]): Alg[G] =
        ${ deriveMapK[Alg, F, G, Err]('af, 'arrow) }
  }

  private def deriveIntercept[Alg[_[_]]: Type, Dom[_]: Type, Cod[_]: Type, Err[_]: Type, F[_]: Type](
      alg: Expr[Alg[F]],
      fk: Expr[FunctionK[[X] =>> Aspect.Weave[F, Dom, Cod, X], F]],
      onRaise: Expr[OnRaise[F, Err]],
      flatMap: Expr[FlatMap[F]]
  )(using q: Quotes): Expr[Alg[F]] =
    import quotes.reflect.*
    val macros = new DeriveRaiseMacros[q.type]
    import macros.*

    // The carrier never changes: source and target of the transform are both
    // `Alg[F]`, so this is the same `TypeRepr` the parameters already mention.
    val Carrier = TypeRepr.of[F]
    val Alg = TypeRepr.of[Alg]
    val algebraName = Expr(Alg.classSymbol.getOrElse(Alg.typeSymbol).name)

    macros.validate(TypeRepr.of[Alg[F]], TypeRepr.of[F], "RaiseAspect")

    def paramAdvice(param: ValDef)(using Quotes): Expr[Seq[Aspect.Advice[Eval, Dom]]] =
      val tpe = param.tpt.tpe
      tpe.widenParam.asType match
        case '[t] =>
          val name = Expr(param.name)
          val value = Ref(param.symbol)
          val dom = macros
            .summonOrAbort(
              TypeRepr.of[Dom].appliedTo(tpe.widenParam),
              s"for parameter ${param.name}",
              // the enclosing generated method, whose given parameters the advice may reference
              param.symbol.owner
            )
            .asExprOf[Dom[t]]
          if tpe.isByName then '{ Aspect.Advice.byName($name, ${ value.asExprOf[t] })(using $dom) :: Nil }
          else if tpe.isRepeated then '{ ${ value.asExprOf[Seq[t]] }.map(Aspect.Advice.byValue($name, _)(using $dom)) }
          else '{ Aspect.Advice.byValue($name, ${ value.asExprOf[t] })(using $dom) :: Nil }

    alg.transformTo[Alg[F]](
      args = {
        case (methodSym, tpe, arg) if macros.capabilityError(tpe, Carrier).isDefined =>
          tpe.dealias.typeArgs.last.asType match
            case '[e] =>
              val errEv = macros
                .summonErrOrAbort(TypeRepr.of[Err], tpe.dealias.typeArgs.last, methodSym)
                .asExprOf[Err[e]]
              '{
                RaiseAspect.observing[F, e, Err](${ arg.asExprOf[Raise[F, e]] }, $onRaise)(using $flatMap, $errEv)
              }.asTerm
      },
      body = {
        case (sym, tpe, body) if tpe.typeSymbol == Carrier.typeSymbol =>
          // Drops every given/implicit clause wholesale rather than leaving a
          // gap, so a using clause sandwiched between two ordinary clauses
          // (Scala 3 only — Scala 2 allows at most one implicit clause, always
          // last) collapses the domain's clause count instead of preserving
          // it. Harmless today because every consumer (TraceParamsOps,
          // WeaveAttributesOps) flattens domain before use; would matter only
          // to a future consumer that compares domain shape structurally.
          val clauses = sym.tree match
            case method: DefDef => method.termParamss.filterNot(c => c.isGiven || c.isImplicit)
            case _ => Nil

          tpe.typeArgs.last.asType match
            case '[t] =>
              given Quotes = sym.asQuotes
              val methodName = Expr(sym.name)
              val cod = macros
                .summonOrAbort(
                  TypeRepr.of[Cod].appliedTo(tpe.typeArgs.last),
                  s"for the result of method ${sym.name}",
                  sym
                )
                .asExprOf[Cod[t]]
              val domain = Expr.ofList(clauses.map { c =>
                val kept = c.params.collect {
                  case p: ValDef if macros.capabilityError(p.tpt.tpe.widenParam, Carrier).isEmpty => p
                }
                '{ List.concat(${ Varargs(kept.map(paramAdvice)) }*) }
              })
              val codomain = '{ Aspect.Advice($methodName, ${ body.asExprOf[F[t]] })(using $cod) }
              '{ $fk.apply[t](Aspect.Weave[F, Dom, Cod, t]($algebraName, $domain, $codomain)) }.asTerm
      }
    )

  private def deriveMapK[Alg[_[_]]: Type, F[_]: Type, G[_]: Type, Err[_]: Type](
      alg: Expr[Alg[F]],
      arrow: Expr[RaiseArrow[F, G, Err]]
  )(using q: Quotes): Expr[Alg[G]] =
    import quotes.reflect.*
    val macros = new DeriveRaiseMacros[q.type]
    import macros.*

    val G = TypeRepr.of[G]

    macros.validate(TypeRepr.of[Alg[F]], TypeRepr.of[F], "RaiseFunctorK")

    alg.transformTo[Alg[G]](
      args = {
        case (methodSym, tpe, arg) if macros.capabilityError(tpe, G).isDefined =>
          tpe.dealias.typeArgs.last.asType match
            case '[e] =>
              val errEv = macros
                .summonErrOrAbort(TypeRepr.of[Err], tpe.dealias.typeArgs.last, methodSym)
                .asExprOf[Err[e]]
              '{ $arrow.pull(${ arg.asExprOf[Raise[G, e]] })(using $errEv) }.asTerm
      },
      body = {
        case (_, tpe, body) if tpe.typeSymbol == G.typeSymbol =>
          tpe.typeArgs.last.asType match
            case '[t] => '{ $arrow.fk(${ body.asExprOf[F[t]] }) }.asTerm
      }
    )
