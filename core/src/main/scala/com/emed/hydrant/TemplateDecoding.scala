package com.emed.hydrant

import cats.data.Validated.{ Invalid, Valid }
import cats.implicits.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.EncoderOps
import com.emed.hydrant.HydrationDefinition.*

trait OrderedMapDecoder {
  given orderedMapDecoder[T: Decoder]: Decoder[List[(String, T)]] = {
    val originalDecoder: Decoder[List[(String, T)]] = Decoder.decodeList
    val objectDecoder: Decoder[List[(String, T)]] =
      Decoder[JsonObject].emap(f => f.toList.traverse { case (k, v) => v.as[T].map(k -> _) }.leftMap(_.message))
    objectDecoder or originalDecoder
  }
}

object TemplateDecoding extends OrderedMapDecoder {

  extension [A](decoder: Decoder[A]) {
    // this is the same as the original circe `or` except `decodeAccumulating` keeps errors from both
    def |||[AA >: A](d: => Decoder[AA]): Decoder[AA] = new Decoder[AA] {
      final def apply(c: HCursor): Decoder.Result[AA] = tryDecode(c)

      override def tryDecode(c: ACursor): Decoder.Result[AA] = {
        decoder.tryDecode(c) match {
          case r @ Right(_) => r
          case Left(_) =>
            d.tryDecode(c)
        }
      }

      override def decodeAccumulating(c: HCursor): Decoder.AccumulatingResult[AA] =
        tryDecodeAccumulating(c)

      override def tryDecodeAccumulating(c: ACursor): Decoder.AccumulatingResult[AA] = {
        decoder.tryDecodeAccumulating(c) match {
          case r @ Valid(_) => r
          case Invalid(errors) =>
            d.tryDecodeAccumulating(c) match {
              case r @ Valid(_)      => r
              case Invalid(orErrors) => Invalid(errors ++ orErrors.toList)
            }
        }
      }
    }
  }

  given decodeEither[A, B](using decoderA: Decoder[A], decoderB: Decoder[B]): Decoder[Either[A, B]] =
    decoderA.map(Left(_)) ||| decoderB.map(Right(_))

  lazy val enumDecoder: Decoder[EnumDefinition] = summon

  lazy val childTemplateDecoder: Decoder[ChildTemplate] = summon

  lazy val templateDecoder: Decoder[Template] = summon

  // .validate is an "early test" for each type.
  // Without this a single missing field will return ~30 error messages, because it tries every decoder.
  val baseDecoder: Decoder[HydrationDefinition] = {
    templateDecoder
      .widen[HydrationDefinition]
      .validate(_.keys.toList.flatten.contains("params"), "Couldn't decode as template - missing .params") |||
    enumDecoder
      .widen[HydrationDefinition]
      .validate(_.keys.toList.flatten.contains("values"), "Couldn't decode as enum - missing .values") |||
    childTemplateDecoder
      .widen[HydrationDefinition]
      .validate(_.keys.toList.flatten.contains("implement"), "Couldn't decode as child template - missing .implement")
  }

  given defnDecoder: Decoder[HydrationDefinition] = new Decoder[HydrationDefinition] {

    override def apply(c: HCursor) = baseDecoder(c)

    override def tryDecode(c: ACursor) = baseDecoder.tryDecode(c)

    override def decodeAccumulating(c: HCursor) = tryDecodeAccumulating(c)

    override def tryDecodeAccumulating(c: ACursor) = baseDecoder.tryDecodeAccumulating(c).leftMap { failures =>
      val id = c.get[String]("id").map(id => f"$id: ").getOrElse("")
      failures.map(f => f.withMessage(id + f.message))
    }
  }

  lazy val enumEncoder: Encoder[EnumDefinition] = summon

  lazy val childTemplateEncoder: Encoder[ChildTemplate] = summon

  lazy val templateEncoder: Encoder[Template] = summon

  given defnEncoder: Encoder[HydrationDefinition] = Encoder.instance {
    case ed: EnumDefinition => ed.asJson
    case ct: ChildTemplate  => ct.asJson
    case t: Template        => t.asJson
  }
}

// This class is used to first add the package name and version to the definition before decoding
case class TemplateDecodingFor(meta: Definitions.Metadata) extends OrderedMapDecoder {
  given defnDecoder: Decoder[HydrationDefinition] = {
    TemplateDecoding.defnDecoder.prepare(_.withFocus(_.mapObject(withFields)))
  }

  private def withFields = {
    setField("packageName", meta.packageName) _ andThen setField("version", meta.version)
  }

  private def setField(field: String, value: Option[String])(obj: JsonObject) = value match {
    case Some(v) => obj.add(field, Json.fromString(v))
    case None    => obj
  }
}
