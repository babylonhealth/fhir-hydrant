package com.emed.hydrant

import cats.implicits.toTraverseOps
import com.emed.hydrant.TemplateJson.{ Arr, Bool, Null, Num, Obj, Primitive }
import com.emed.hydrant.TemplateString.{ templateTokenEscapedRegex, templateTokenRegex }
import io.circe.Decoder.decodeJson
import io.circe.Encoder.encodeJson
import io.circe.{ Decoder, Encoder, Json, JsonNumber }

import scala.collection.immutable.ListMap

sealed trait TemplateJson {
  def apply(key: String): Option[TemplateJson] = asObject.flatMap(_.fields.get(key))

  def asBoolean: Option[Boolean] = this match {
    case Bool(bool) => Some(bool)
    case _          => None
  }

  def asNumber: Option[JsonNumber] = this match {
    case Num(num) => Some(num)
    case _        => None
  }

  /** Returns the template string - doesn't necessarily contain any tokens */
  def asString: Option[TemplateString] = this match {
    case s: TemplateString => Some(s)
    case _                 => None
  }

  /** Returns the template if it is a string with no template params */
  def asLiteralString: Either[Exception, String] = Left(new Exception(f"Expected a literal string, but was $this"))

  def asLiteralOrDie: Json = asLiteral.toTry.get

  def asArray: Option[Vector[TemplateJson]] = this match {
    case Arr(arr) => Some(arr)
    case _        => None
  }

  def asObject: Option[Obj] = this match {
    case obj: Obj => Some(obj)
    case _        => None
  }

  def findAllByKey(key: String): Vector[TemplateJson] = this match {
    case _: Primitive => Vector.empty
    case obj: Obj     => obj.values.toVector.flatMap(_.findAllByKey(key)) ++ obj(key)
    case Arr(arr)     => arr.flatMap(_.findAllByKey(key))
  }

  /** Returns the literal unescaped JSON if there are no template tokens remaining */
  def asLiteral: Either[RemainingTemplateValuesError, Json] = this match {
    case Null              => Right(Json.Null)
    case Bool(bool)        => Right(Json.fromBoolean(bool))
    case Num(num)          => Right(Json.fromJsonNumber(num))
    case s: TemplateString => s.asLiteralString.map(Json.fromString)
    case Obj(obj)          => obj.toVector.traverse { case key -> value => value.asLiteral.map(key -> _) }.map(Json.fromFields)
    case Arr(arr)          => arr.traverse(_.asLiteral).map(Json.fromValues)
  }

  /** Convert to JSON without any handling of remaining template tokens */
  private def json: Json = this match {
    case Null                => Json.Null
    case Bool(bool)          => Json.fromBoolean(bool)
    case Num(number)         => Json.fromJsonNumber(number)
    case TemplateString(str) => Json.fromString(str)
    case Arr(arr)            => Json.arr(arr.map(_.json): _*)
    case Obj(obj)            => Json.fromFields(obj.map { case key -> value => key -> value.json })
  }

  def isLiteral: Boolean = asLiteral.isRight

  def fold[X](
      jsonNull: => X,
      jsonBoolean: Boolean => X,
      jsonNumber: JsonNumber => X,
      jsonString: TemplateString => X,
      jsonArray: Vector[TemplateJson] => X,
      jsonObject: Obj => X
  ): X = this match {
    case Null              => jsonNull
    case Bool(bool)        => jsonBoolean(bool)
    case Num(num)          => jsonNumber(num)
    case s: TemplateString => jsonString(s)
    case Arr(arr)          => jsonArray(arr)
    case obj: Obj          => jsonObject(obj)
  }

  def arrayOrObject[X](
      or: => X,
      jsonArray: Vector[TemplateJson] => X,
      jsonObject: Obj => X
  ): X = this match {
    case _: Primitive => or
    case Arr(arr)     => jsonArray(arr)
    case obj: Obj     => jsonObject(obj)
  }

  def contains(templateString: TemplateString): Boolean = this match {
    case s: TemplateString => s containsTemplateString templateString
    case _: Primitive      => false
    case Arr(arr)          => arr.exists(_ contains templateString)
    case obj: Obj          => obj.values.exists(_ contains templateString)
  }

  override def toString: String = json.spaces2

  def hasRemainingTemplateParams: Boolean = remainingTemplateParams.nonEmpty

  def remainingTemplateParams: Set[String] = this match {
    case s: TemplateString         => s.tokens.toSet
    case _: TemplateJson.Primitive => Set.empty
    case obj: Obj                  => obj.values.toSet.flatMap(_.remainingTemplateParams)
    case Arr(arr)                  => arr.toSet.flatMap(_.remainingTemplateParams)
  }

  /** get the path of a token within the template * */
  def templateTokenPath(token: String, path: String = ""): String = this match {
    case s: TemplateString if s.tokens contains token => path
    case _: TemplateJson.Primitive                    => ""
    case Arr(arr)                                     => arr.map(_.templateTokenPath(token, path)).find(_.nonEmpty).getOrElse("")
    case Obj(obj) =>
      obj
        .map { case (field, value) => value.templateTokenPath(token, if (path.isEmpty) field else s"$path.$field") }
        .find(_.nonEmpty)
        .getOrElse("")
  }

  /** get the parent Json Object of a token field or JsonNull, i.e { "code": "{{{code}}}" "system": "asystem" }
    */
  def templateTokenParentObj(token: String): Option[Obj] =
    templateTokenParentJson(token, this).asObject

  private def templateTokenParentJson(token: String, template: TemplateJson): TemplateJson = template match {
    case s: TemplateString if s.tokens.contains(token) => s
    case _: TemplateJson.Primitive                     => TemplateJson.Null
    case Arr(arr) => arr.map(templateTokenParentJson(token, _)).collectFirst { case o: Obj => o }.getOrElse(TemplateJson.Null)
    case obj: Obj =>
      obj.values
        .map {
          templateTokenParentJson(token, _) match {
            case _: TemplateString => obj
            case o: Obj            => o
            case _                 => TemplateJson.Null
          }
        }
        .collectFirst { case o: Obj => o }
        .getOrElse(TemplateJson.Null)
  }

  def followPath(path: String): Vector[TemplateJson] = {
    def down(field: String) = arrayOrObject(Vector.empty, _.flatMap(_(field)), _(field).toVector)

    path match {
      case s"$init.$remaining" => down(init).flatMap(_.followPath(remaining))
      case last                => down(last)
    }
  }
}

object TemplateJson {
  sealed trait Primitive          extends TemplateJson
  object Null                     extends Primitive
  case class Bool(bool: Boolean)  extends Primitive
  case class Num(num: JsonNumber) extends Primitive
  case class Obj(fields: ListMap[String, TemplateJson]) extends TemplateJson {
    def +(pair: (String, TemplateJson)): Obj = Obj(fields + pair)
    def keys: Set[String]                    = fields.keys.toSet
    def values: Iterable[TemplateJson]       = fields.values
  }
  case class Arr(values: Vector[TemplateJson]) extends TemplateJson

  object Arr {
    def apply(values: TemplateJson*): Arr = Arr(values.toVector)
  }

  object Obj {
    def apply(obj: (String, TemplateJson)*): Obj       = from(obj)
    def from(o: Iterable[(String, TemplateJson)]): Obj = Obj(ListMap.from(o))
  }

  // Turn a literal JSON object into a template JSON, escaping any template tokens within it
  def fromLiteralJson(json: Json): TemplateJson = json.fold(
    jsonNull = Null,
    jsonBoolean = Bool.apply,
    jsonNumber = Num.apply,
    jsonString = TemplateString.literal.apply,
    jsonArray = a => Arr(a.map(fromLiteralJson)),
    jsonObject = o => Obj.from(o.toVector.map { case k -> v => k -> fromLiteralJson(v) })
  )

  implicit val encoder: Encoder[TemplateJson] = encodeJson.contramap(_.json)
  implicit val decoder: Decoder[TemplateJson] = decodeJson.map(fromJson)

  /** Convert from JSON, interpreting it as a template (as opposed to fromLiteralJson which will escape tokens) */
  private def fromJson(json: Json): TemplateJson = json.fold(
    jsonNull = Null,
    jsonBoolean = Bool.apply,
    jsonNumber = Num.apply,
    jsonString = TemplateString.apply,
    jsonArray = a => Arr(a.map(fromJson)),
    jsonObject = o => Obj.from(o.toVector.map { case k -> v => k -> fromJson(v) })
  )
}

/** A string that might contain a `{{{templateToken}}}`. Handles escaping and unescaping literal values to prevent injecting template tokens
  * where they are not expected.
  */
case class TemplateString private[hydrant] (val inner: String) extends Primitive {

  def isSingleToken: Boolean = templateTokenRegex.matches(inner)

  def asLiteralStringOrDie: String = asLiteralString.toTry.get

  def substitute(paramName: String, value: String): TemplateString =
    TemplateString(inner.replace(TemplateString.token(paramName).inner, TemplateString.literal(value).inner))

  def tokens: Vector[String] = templateTokenRegex.findAllMatchIn(inner).toVector.map(_.group(1))

  /** Returns the template if it is a string with no template params */
  override def asLiteralString: Either[RemainingTemplateValuesError, String] = tokens match {
    case Vector() =>
      Right(
        inner
          .replace("\\}", "}")
          .replace("\\{", "{")
          .replace("\\\\", "\\"))
    case tokens => Left(RemainingTemplateValuesError(None, tokens.toSet))
  }

  def containsTemplateString(templateString: TemplateString): Boolean = inner contains templateString.inner

  /** Extract a param value from an already-templated string */
  def extractParamValue(paramName: String, value: String): Option[String] = {
    def escapeRegex(str: String) = str.flatMap {
      case c if specialChars contains c => s"\\$c"
      case c                            => c.toString
    }

    val qReg = templateTokenEscapedRegex
      .replaceAllIn(escapeRegex(inner).replace(escapeRegex(TemplateString.token(paramName).inner), "(.*)"), ".*")
      .r

    value match {
      case qReg(paramValue) => Some(paramValue)
      case _                => None
    }
  }
}

object TemplateString {
  private val templateTokenRegex        = """\{\{\{([a-zA-Z0-9_\-]+)}}}""".r
  private val templateTokenEscapedRegex = """\\\{\\\{\\\{([a-zA-Z0-9_\-]+)}}}""".r

  object token {
    def apply(paramName: String): TemplateString     = TemplateString(f"{{{$paramName}}}")
    def unapply(str: TemplateString): Option[String] = templateTokenRegex.unapplySeq(str.inner).map(_.head)
  }

  // Take a literal string, escaping any special characters (for tokens) inside it
  object literal {
    def apply(string: String): TemplateString = TemplateString(
      string
        .replace("\\", "\\\\")
        .replace("{", "\\{")
        .replace("}", "\\}"))
    def unapply(str: TemplateString): Option[String] = str.asLiteralString.toOption
  }
}
