package com.emed

import com.emed.hydrant.HydrantError.ErrorOr
import io.circe.Encoder

import scala.util.matching.Regex
import scala.util.matching.Regex.Groups

package object hydrant {
  val specialChars            = """.^$*+?{()[\|""".toSet
  val childTemplateEnumKey    = "type" // TODO allow override this
  val childTemplateEnumPrefix = "ChildTemplate"

  private val extensionTokens = Set('.', ':')

  def camelToUnderscores(name: String) = "[A-Z\\d]".r
    .replaceAllIn(
      name,
      { m =>
        "_" + m.group(0).toLowerCase()
      })
    .dropWhile(_ == '_')

  def childTemplateIdToEnumValue(templateid: String, parentid: String, parentBaseName: String): String =
    camelToCapSnake(templateid.replace(parentid, parentBaseName + childTemplateEnumPrefix))

  def childTemplateEnumValueToId(enumValue: String, parentid: String, parentBaseName: String): String =
    capSnaketoCamel(enumValue).replace(parentBaseName + childTemplateEnumPrefix, parentid)

  def camelToCapSnake(toConvert: String) =
    toConvert.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z\\d])([A-Z])", "$1_$2").toUpperCase
  def capSnaketoCamel(toConvert: String) = toConvert.split("_").map(_.toLowerCase.capitalize).mkString
}
