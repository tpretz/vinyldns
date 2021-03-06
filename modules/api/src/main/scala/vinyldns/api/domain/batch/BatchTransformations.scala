/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
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
 */

package vinyldns.api.domain.batch

import vinyldns.api.domain.dns.DnsConversions.{getIPv4NonDelegatedZoneName, getIPv6FullReverseName}
import vinyldns.core.domain.batch._
import vinyldns.core.domain.record.{RecordSet, RecordSetChange}
import vinyldns.core.domain.record.RecordType._
import vinyldns.core.domain.zone.Zone
import vinyldns.core.domain.record.RecordType.RecordType

object SupportedBatchChangeRecordTypes {
  val supportedTypes = Set(A, AAAA, CNAME, PTR, TXT, MX)
  def get: Set[RecordType] = supportedTypes
}

/* Helper types for intermediate transformations of the batch data */
// ALL of these are subject to change as implementation needs
object BatchTransformations {

  final case class ExistingZones(zones: Set[Zone]) {
    val zoneMap: Map[String, Zone] = zones.map(z => (z.name, z)).toMap

    def getById(id: String): Boolean = zones.exists(zn => zn.id.equals(id))

    def getByName(name: String): Option[Zone] = zoneMap.get(name)

    def getipv4PTRMatches(ipv4: String): List[Zone] =
      getIPv4NonDelegatedZoneName(ipv4).toList.flatMap { name =>
        zones.filter(_.name.endsWith(name))
      }

    def getipv6PTRMatches(ipv6: String): List[Zone] = {
      val fullReverseZone = getIPv6FullReverseName(ipv6)
      fullReverseZone.toList.flatMap { fqdn =>
        zones.filter(zn => fqdn.endsWith(zn.name))
      }
    }
  }

  final case class ExistingRecordSets(recordSets: List[RecordSet]) {
    val recordSetMap: Map[(String, String), List[RecordSet]] =
      recordSets.groupBy(rs => (rs.zoneId, rs.name.toLowerCase))

    def get(zoneId: String, name: String, recordType: RecordType): Option[RecordSet] =
      recordSetMap.getOrElse((zoneId, name.toLowerCase), List()).find(_.typ == recordType)

    def containsRecordSetMatch(zoneId: String, name: String): Boolean =
      recordSetMap.contains(zoneId, name.toLowerCase)

    def getRecordSetMatch(zoneId: String, name: String): List[RecordSet] =
      recordSetMap.getOrElse((zoneId, name.toLowerCase), List())
  }

  sealed trait ChangeForValidation {
    val zone: Zone
    val recordName: String
    val inputChange: ChangeInput
    val recordKey = RecordKey(zone.id, recordName, inputChange.typ)
    def asNewStoredChange: SingleChange
    def isAddChangeForValidation: Boolean
    def isDeleteChangeForValidation: Boolean
  }

  object ChangeForValidation {
    def apply(zone: Zone, recordName: String, changeInput: ChangeInput): ChangeForValidation =
      changeInput match {
        case a: AddChangeInput => AddChangeForValidation(zone, recordName, a)
        case d: DeleteChangeInput => DeleteChangeForValidation(zone, recordName, d)
      }
  }

  final case class AddChangeForValidation(
      zone: Zone,
      recordName: String,
      inputChange: AddChangeInput)
      extends ChangeForValidation {
    def asNewStoredChange: SingleChange =
      SingleAddChange(
        zone.id,
        zone.name,
        recordName,
        inputChange.inputName,
        inputChange.typ,
        inputChange.ttl,
        inputChange.record,
        SingleChangeStatus.Pending,
        None,
        None,
        None)

    def isAddChangeForValidation: Boolean = true

    def isDeleteChangeForValidation: Boolean = false
  }

  final case class DeleteChangeForValidation(
      zone: Zone,
      recordName: String,
      inputChange: DeleteChangeInput)
      extends ChangeForValidation {
    def asNewStoredChange: SingleChange =
      SingleDeleteChange(
        zone.id,
        zone.name,
        recordName,
        inputChange.inputName,
        inputChange.typ,
        SingleChangeStatus.Pending,
        None,
        None,
        None)

    def isAddChangeForValidation: Boolean = false

    def isDeleteChangeForValidation: Boolean = true
  }

  final case class BatchConversionOutput(
      batchChange: BatchChange,
      recordSetChanges: List[RecordSetChange])

  final case class ChangeForValidationMap(changes: List[ChangeForValidation]) {
    val innerMap: Map[RecordKey, List[ChangeForValidation]] = changes.groupBy(_.recordKey)

    def getList(recordKey: RecordKey): List[ChangeForValidation] =
      innerMap.getOrElse(recordKey, List())

    def containsAddChangeForValidation(recordKey: RecordKey): Boolean = {
      val changeList = getList(recordKey)
      changeList.nonEmpty && changeList.exists(_.isAddChangeForValidation)
    }

    def containsDeleteChangeForValidation(recordKey: RecordKey): Boolean = {
      val changeList = getList(recordKey)
      changeList.nonEmpty && changeList.exists(_.isDeleteChangeForValidation)
    }
  }
}
