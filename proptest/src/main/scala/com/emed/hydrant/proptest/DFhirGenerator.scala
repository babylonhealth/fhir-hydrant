package com.emed.hydrant.proptest

import cats.effect.unsafe.implicits.global
import com.emed.hydrant.*
import com.emed.hydrant.PrimitiveParamType.*
import io.circe.Json
import org.scalacheck.rng.Seed
import org.scalacheck.{Arbitrary, Gen}
import cats.implicits.*

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import java.util.{Base64, Optional}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** Generates random dfhir
  * @param getValidCode
  *   given a value set url, get a code within that value set
  */
class DFhirGenerator(definitions: Definitions) {

  private lazy val childTemplateIndex: Map[String, List[String]] =
    definitions.all.toList
      .collect { case template: ChildTemplate => template.`extends` -> template }
      .groupMap { case (parentId, _) => parentId } { case (_, childTemplate) => childTemplate.id }

  def generateSample(templateId: String, maxSize: Int = 100): Option[Json] =
    definitions(templateId).toOption.flatMap {
      dehydratedGen(_)(Gen.Parameters.default.withSize(maxSize), Seed.random())
    }

  private val generateCalStr: Gen[String] = {
    val startDateSec = LocalDateTime.parse("1970-01-01T00:00:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME).toEpochSecond(ZoneOffset.UTC)
    val endDateSec   = LocalDateTime.parse("2099-01-01T00:00:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME).toEpochSecond(ZoneOffset.UTC)
    Gen.choose(startDateSec, endDateSec).map(i => LocalDateTime.ofEpochSecond(i, 0, ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString)
  }

  def typeToGen(tpe: ParamType, paramName: String = ""): Gen[Json] = {
    tpe match {
      case `uuid`                                                    => Gen.uuid.map(Json fromString _.toString)
      case `string` if paramName == "id" || paramName.endsWith("Id") =>
        // It's a bit of a hack - but the regular "string" likes to shrink to "".
        // This was causing flakiness for dehydration over inline references.
        // Strings named "id" probably shouldn't have multiple with the same id "" anyway.
        // So I think its reasonable to weaken the test in this way.
        Gen.choose(4, 20).flatMap(Gen.listOfN(_, Gen.alphaChar)).map(Json fromString _.mkString)
      case `string` => Gen.alphaNumStr.map(Json.fromString)
      case `code`   => Gen.listOfN(10, Gen.oneOf(Gen.oneOf('-', '_'), Gen.alphaChar, Gen.alphaChar)).map(Json fromString _.mkString)
      case `dateTime` | `instant` => generateCalStr.map(Json.fromString)
      case `date`                 => generateCalStr.map(Json fromString _.takeWhile(_ != 'T'))
      case `integer`              => Arbitrary.arbInt.arbitrary.map(Json.fromInt)
      case `boolean`              => Arbitrary.arbBool.arbitrary.map(Json.fromBoolean)
      case `decimal`              => Arbitrary.arbFloat.arbitrary.map(Json.fromFloat).filter(_.isDefined).map(_.get)
      case `int64`                => Arbitrary.arbLong.arbitrary.map(Json.fromLong)
      case `base64Binary` => Gen.listOf(Arbitrary.arbByte.arbitrary).map(l => Json fromString Base64.getEncoder.encodeToString(l.toArray))
      case `time`         => generateCalStr.map(Json fromString _.dropWhile(_ != 'T').tail)
      case `unsignedInt`  => Gen.chooseNum(0, Int.MaxValue).map(Json.fromInt)
      case `positiveInt`  => Gen.posNum[Int].map(Json.fromInt)
      case `xhtml`        => Gen.const(Json fromString "<div/>")
      case `markdown`     => Gen.const(Json fromString "# A header\nAnd some text")
      case `id`           => Gen.alphaStr.filter(s => s.nonEmpty && s.length <= 64).map(Json.fromString)
      case `canonical` | `oid` | `uri` | `url` =>
        Gen
          .listOfN(10, Gen.oneOf(Gen.oneOf('-', '_'), Gen.alphaChar, Gen.alphaChar))
          .map(cs => Json fromString s"https://fhir,example.com/${cs.mkString}/${cs.mkString.reverse}")
      case ComplexType(typeName) => dehydratedGen(definitions(typeName).toTry.get, topLevel = false)
    }
  }

  def dehydratedGen(defn: HydrationDefinition, topLevel: Boolean = true): Gen[Json] =
    (defn match {
      case e: EnumDefinition =>
        Gen.oneOf(e.validNames.map(_.fold(Json.Null)(Json.fromString)))
      case t: Template =>
        val fieldsGen: Seq[Gen[List[(String, Json)]]] =
          t.params.filter { case (_, paramInfo) => !paramInfo.isAbstract && (!paramInfo.isProvided || topLevel) }.map {
            // hack for ras-2 on RiskAssessment.prediction.probability
            case ("probability", paramInfo) if paramInfo.`type` == decimal =>
              val genPercentage = Gen.chooseNum(0d, 100d) map (Json.fromDouble(_).get) map ("probability" -> _)
              if (paramInfo.isOptional) Gen.option(genPercentage).map(_.toList)
              else genPercentage.map(List(_))
            case (paramName, paramInfo) if !paramInfo.isOptional && !paramInfo.isRepeated =>
              val gen = typeToGen(paramInfo.`type`, paramName)
              if (paramInfo.isFlattened && paramInfo.isComplexType)
                gen.map(j => j.asObject.map(_.toList).getOrElse(List(paramName -> j)))
              else
                gen.map(paramName -> _).map(List(_))
            case (paramName, paramInfo) if !paramInfo.isRepeated =>
              val gen = Gen.option(typeToGen(paramInfo.`type`, paramName))
              if (paramInfo.isFlattened && paramInfo.isComplexType)
                gen.map(_.map(j => j.asObject.map(_.toList).getOrElse(List(paramName -> j))).getOrElse(Nil))
              else gen.map(_.map(paramName -> _).toList)
            case (paramName, paramInfo) if paramInfo.isOptional =>
              Gen.option(smallerNonEmptyListOf(typeToGen(paramInfo.`type`, paramName))).map(_.map(paramName -> Json.fromValues(_)).toList)
            case (paramName, paramInfo) =>
              smallerNonEmptyListOf(typeToGen(paramInfo.`type`, paramName)).map(paramName -> Json.fromValues(_)).map(List(_))
          }
        val subTypeGen =
          if (t.isParent)
            Gen
              .option(
                Gen
                  .oneOf(childTemplateIndex.getOrElse(t.id, throw new Exception(s"Could not find child template for ${t.id}")))
              )
              .map(_.map(id => childTemplateEnumKey -> Json.fromString(childTemplateIdToEnumValue(id, t.id, t.enumBaseName))).toList)
          else
            Gen.const(None)
        Gen.sequence(fieldsGen :+ subTypeGen).map(Json fromFields _.asScala.toList.flatten.filter { case (_, value) => !value.isNull })
      case child: ChildTemplate =>
        val parent = definitions(child.`extends`).toTry.get
        dehydratedGen(parent).map {
          _.mapObject(_.add(childTemplateEnumKey, Json fromString childTemplateIdToEnumValue(child.id, parent.id, parent.enumBaseName)))
        }
    }).filterNot(j => j.isObject && j.asObject.get.isEmpty)

  // Make sure nested objects don't get huge
  private def smallerNonEmptyListOf[T](gen: Gen[T]): Gen[List[T]] = for {
    size    <- Gen.size
    listGen <- Gen.nonEmptyListOf(Gen.resize(Math.log(size).toInt.max(1), gen))
  } yield listGen
}
