package com.emed.hydrant

import com.emed.hydrant.{ ChildTemplate, HydrationDefinition, ParamInfo }

import java.time.format.DateTimeFormatter

package object profilegen {

  def isParamAbstractWithImpls(
      paramName: String,
      paramInfo: ParamInfo,
      templateId: String,
      definitions: Iterable[HydrationDefinition],
      group: Option[String]): Boolean =
    paramInfo.isAbstract && (!paramInfo.isOptional || definitions
      .collect {
        case c: ChildTemplate if c.`extends` == templateId && group.forall(c.group.contains) => c.implement
      }
      .flatten
      .exists {
        case (`paramName`, _) => true
        case _                => false
      })

}
