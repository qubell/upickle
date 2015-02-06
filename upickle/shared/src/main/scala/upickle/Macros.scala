package upickle

import ScalaVersionStubs._
import scala.annotation.StaticAnnotation
import scala.language.experimental.macros

/**
 * Used to annotate either case classes or their fields, telling uPickle
 * to use a custom string as the key for that class/field rather than the
 * default string which is the full-name of that class/field.
 */
class key(s: String) extends StaticAnnotation

/**
 * Implementation of macros used by uPickle to serialize and deserialize
 * case classes automatically. You probably shouldn't need to use these
 * directly, since they are called implicitly when trying to read/write
 * types you don't have a Reader/Writer in scope for.
 */
object Macros {
  class RW(val short: String, val long: String, val actionNames: Seq[String])

  object RW {
    object R extends RW("R", "Reader", Seq("apply"))
    object W extends RW("W", "Writer", Seq("unapply", "unapplySeq"))
  }

  def macroRImpl[T: c.WeakTypeTag](c: Context) = {
    import c.universe._
    val tpe = weakTypeTag[T].tpe

    assert(!tpe.typeSymbol.fullName.startsWith("scala."))

    c.Expr[Reader[T]] {
      val x = picklerFor(c)(tpe, RW.R)(
        _.map(p => q"$p.read": Tree)
         .reduce((a, b) => q"$a orElse $b")
      )

      val msg = "Tagged Object " + tpe.typeSymbol.fullName
      q"""upickle.Internal.validateReader($msg){$x}"""
    }
  }

  def macroWImpl[T: c.WeakTypeTag](c: Context) = {
    import c.universe._
    val tpe = weakTypeTag[T].tpe

    assert(!tpe.typeSymbol.fullName.startsWith("scala."))

    c.Expr[Writer[T]] {
      picklerFor(c)(tpe, RW.W) { things =>
        if (things.length == 1) q"upickle.Internal.merge0(${things(0)}.write)"
        else things.map(p => q"$p.write": Tree)
                   .reduce((a, b) => q"upickle.Internal.merge($a, $b)")
      }
    }
  }

  /**
   * Generates a pickler for a particular type
   *
   * @param tpe The type we are generating the pickler for
   * @param rw Configuration that determines whether it's a Reader or
   *           a Writer, together with the various names which vary
   *           between those two choices
   * @param treeMaker How to merge the trees of the multiple subpicklers
   *                  into one larger tree
   */
  def picklerFor(c: Context)
                (tpe: c.Type, rw: RW)
                (treeMaker: Seq[c.Tree] => c.Tree): c.Tree =
  {
    val pick =
      if (tpe.typeSymbol.asClass.isTrait) pickleTrait(c)(tpe, rw)(treeMaker)
      else if (tpe.typeSymbol.isModuleClass) pickleCaseObject(c)(tpe, rw)(treeMaker)
      else pickleClass(c)(tpe, rw)(treeMaker)

    import c.universe._

    val knotName = newTermName("knot" + rw.short)

    val i = c.fresh[TermName]("i")
    val x = c.fresh[TermName]("x")

    q"""
       upickle.Internal.$knotName{implicit $i: upickle.Knot.${newTypeName(rw.short)}[$tpe] =>
          val $x = $pick
          $i.copyFrom($x)
          $x
        }
     """
  }

  def pickleTrait(c: Context)
                 (tpe: c.Type, rw: RW)
                 (treeMaker: Seq[c.Tree] => c.Tree): c.universe.Tree =
  {
    val clsSymbol = tpe.typeSymbol.asClass

    if (!clsSymbol.isSealed) {
      val msg = s"[error] The referenced trait [[${clsSymbol.name}]] must be sealed."
      Console.err.println(msg)
      c.abort(c.enclosingPosition, msg) /* TODO Does not show message. */
    }

    if (clsSymbol.knownDirectSubclasses.isEmpty) {
      val msg = s"The referenced trait [[${clsSymbol.name}]] does not have any sub-classes. This may " +
        "happen due to a limitation of scalac (SI-7046) given that the trait is " +
        "not in the same package. If this is the case, the hierarchy may be " +
        "defined using integer constants."
      Console.err.println(msg)
      c.abort(c.enclosingPosition, msg) /* TODO Does not show message. */
    }

    val subPicklers =
      clsSymbol.knownDirectSubclasses.map(subCls =>
        picklerFor(c)(subCls.asType.toType, rw)(treeMaker)
      ).toSeq

    val combined = treeMaker(subPicklers)

    import c.universe._
    q"""upickle.${newTermName(rw.long)}[$tpe]($combined)"""
  }

  def pickleCaseObject(c: Context)
                      (tpe: c.Type, rw: RW)
                      (treeMaker: Seq[c.Tree] => c.Tree) =
  {
    val mod = tpe.typeSymbol.asClass.module

    import c.universe._
    annotate(c)(tpe)(q"upickle.Internal.${newTermName("Case0"+rw.short)}[$tpe]($mod)")
  }

  /** If there is a sealed base class, annotate the pickled tree in the JSON
    * representation with a class label.
    */
  def annotate(c: Context)
              (tpe: c.Type)
              (pickler: c.universe.Tree) =
  {
    import c.universe._
    val sealedParent = tpe.baseClasses.find(_.asClass.isSealed)
    sealedParent.fold(pickler) { parent =>
      val index = customKey(c)(tpe.typeSymbol).getOrElse(tpe.typeSymbol.fullName)
      q"implicitly[upickle.Annotator].annotate($pickler, $index)"
    }
  }

  /**
   * Get the custom @key annotation from the parameter Symbol if it exists
   */
  def customKey(c: Context)(sym: c.Symbol): Option[String] = {
    import c.universe._
    sym.annotations
      .find(_.tpe == typeOf[key])
      .flatMap(_.scalaArgs.headOption)
      .map{case Literal(Constant(s)) => s.toString}
  }

  def pickleClass(c: Context)
                 (tpe: c.Type, rw: RW)
                 (treeMaker: Seq[c.Tree] => c.Tree) =
  {
    import c.universe._

    val companion = companionTree(c)(tpe)

    val apply =
      companion.tpe
        .member(newTermName("apply"))

    if (apply == NoSymbol){
      c.abort(
        c.enclosingPosition,
        s"Don't know how to pickle $tpe; it's companion has no `apply` method"
      )
    }

    val argSyms = apply.asMethod.paramss.flatten

    var hasVarargs = false
    val argSymTypes = argSyms.map(_.typeSignature).map{
      case TypeRef(a, b, c)  if b.toString == "class <repeated>" =>
        typeOf[Seq[String]] match{
          case TypeRef(_, b2, _) =>
            hasVarargs = true
            TypeRef(a, b2, c)
        }
      case t => t
    }

    val (originalArgNames, argNames) = argSyms.map { p =>
      val originalName = p.name.toString
      (originalName, customKey(c)(p).getOrElse(originalName))
    }.unzip

    val defaults = argSyms.zipWithIndex.map { case (s, i) =>
      val defaultName = newTermName("apply$default$" + (i + 1))
      companion.tpe.member(defaultName) match{
        case NoSymbol => q"None"
        case _ => q"Some($companion.$defaultName)"
      }
    }

    val tpeTypeArgs = tpe.normalize match {
      case TypeRef(_, _, args) => args
      case _ => c.abort(
        c.enclosingPosition,
        s"Don't know how to pickle type $tpe"
      )
    }
    val applyTypeArgs = apply.asMethod.typeParams

    def substitute(t: Type): Type = t.substituteTypes(applyTypeArgs, tpeTypeArgs)

    val pickler = if (rw == RW.R) {
      val objectName = newTermName(c.fresh("o"))
      val mapName = newTermName(c.fresh("m"))
      val filterName = newTermName(c.fresh("filter"))

      val filterInvocations0 = (argNames, defaults, argSymTypes).zipped.map { (name, default, `type`) =>
        val argName = newTermName(c.fresh("f"))
        val argVal = q"val $argName: ${tq""}"
        val argType = substitute(`type`)
        // we have to be explicit in implicit parameters to avoid diverging implicit expansion error
        q"""$filterName.readField[$argType](
              $name, $mapName.get($name), $default
            )(implicitly[upickle.Reader[$argType]], implicitly[scala.reflect.ClassTag[$argType]])
             .fold($argVal => throw $argName($objectName), identity)
         """
      }.toVector
      val filterInvocations =
        if (hasVarargs) filterInvocations0.init :+ q"${filterInvocations0.last}: _*"
        else filterInvocations0

      val instantiation =
        if (filterInvocations.isEmpty) q"$companion.apply()"
        else
          q"""{
              val $mapName = $objectName.value.toMap
              val $filterName = implicitly[upickle.Filter]
              $companion.apply[..$tpeTypeArgs](..$filterInvocations)
           }"""

      q"""
          upickle.Reader.apply[$tpe] {
            case $objectName: upickle.Js.Obj =>
              $instantiation
          }
       """
    } else {  // RW.W
      val valueName = newTermName(c.fresh("value"))
      val valueParam = q"val $valueName: ${tq""}"
      val filterName = newTermName(c.fresh("filter"))

      val actionName = Vector("unapply", "unapplySeq")
        .map(newTermName(_))
        .find(companion.tpe.member(_) != NoSymbol)
        .getOrElse(c.abort(c.enclosingPosition, s"No unapply nor unapplySeq methods are defined in companion object of $tpe"))

      val generatedPatternNames = argNames.map(c.fresh).map(newTermName(_))

      val extractStatement = if (argNames.nonEmpty) {
        val namePattern = if (argNames.size == 1) pq"${generatedPatternNames.head}"
        else pq"(..$generatedPatternNames)"
        q"val Some($namePattern) = $companion.$actionName[..$tpeTypeArgs]($valueName)"
      } else q""  // no fields, nothing to extract

      val filterInvocations = (generatedPatternNames zip argNames, defaults, argSymTypes)
        .zipped.map { (names, default, `type`) =>
          val (patternName, name) = names
          val argType = substitute(`type`)
          // we have to be explicit in implicit parameters to avoid diverging implicit expansion error
          q"""$filterName.writeField[$argType](
                $name, $patternName, $default
              )(implicitly[upickle.Writer[$argType]], implicitly[scala.reflect.ClassTag[$argType]])
              .map($name -> _)"""
        }
      val instantiation =
        if (filterInvocations.isEmpty) q"upickle.Js.Obj()"
        else
          q"""
             ..$extractStatement
             val $filterName = implicitly[upickle.Filter]
             upickle.Js.Obj(Iterator(..$filterInvocations).flatten.toArray: _*)
           """

      q"""
          upickle.Writer.apply[$tpe] { $valueParam =>
            ..$instantiation
          }
       """
    }

    annotate(c)(tpe)(pickler)
  }

  def companionTree(c: Context)(tpe: c.Type) = {
    import c.universe._
    val companionSymbol = tpe.typeSymbol.companionSymbol

    if (companionSymbol == NoSymbol) {
      val clsSymbol = tpe.typeSymbol.asClass
      val msg = "[error] The companion symbol could not be determined for " +
        s"[[${clsSymbol.name}]]. This may be due to a bug in scalac (SI-7567) " +
        "that arises when a case class within a function is pickled. As a " +
        "workaround, move the declaration to the module-level."
      Console.err.println(msg)
      c.abort(c.enclosingPosition, msg) /* TODO Does not show message. */
    }

    val symTab = c.universe.asInstanceOf[reflect.internal.SymbolTable]
    val pre = tpe.asInstanceOf[symTab.Type].prefix.asInstanceOf[Type]
    c.universe.treeBuild.mkAttributedRef(pre, companionSymbol)
  }
}
 
