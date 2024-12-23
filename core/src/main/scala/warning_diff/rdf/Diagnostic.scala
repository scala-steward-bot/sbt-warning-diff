package warning_diff.rdf

import sjsonnew.BasicJsonProtocol.*
import sjsonnew.JsonFormat
import warning_diff.JsonClassOps.*

case class Diagnostic(
  message: String,
  location: Location,
  severity: Option[String]
) extends DiagnosticCompat {
  override def toString = this.toJsonString
}

object Diagnostic {
  implicit val instance: JsonFormat[Diagnostic] =
    caseClass3(apply, (_: Diagnostic).toTupleOption)(
      "message",
      "location",
      "severity"
    )
}
