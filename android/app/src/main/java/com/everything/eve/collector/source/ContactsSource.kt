package com.everything.eve.collector.source

import android.content.ContentResolver
import android.provider.ContactsContract
import com.everything.eve.collector.core.CollectorCursor
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.collector.core.CollectorSource
import com.everything.eve.collector.core.ContactAddress
import com.everything.eve.collector.core.ContactData
import com.everything.eve.collector.core.ContactEmail
import com.everything.eve.collector.core.ContactName
import com.everything.eve.collector.core.ContactPhone
import com.everything.eve.collector.core.TypeMaps

/**
 * 通讯录数据源（两阶段联查）：
 *  1) ContactsContract.Contacts 按游标增量取聚合联系人
 *     （时间列 CONTACT_LAST_UPDATED_TIMESTAMP，API 18+，minSdk 26 可用）；
 *  2) 对每个联系人查 ContactsContract.Data 明细行
 *     （姓名/电话/邮箱/地址/组织/生日事件/备注），聚合为 ContactData。
 *
 * 仅读取映射，不做任何日志输出（零知识：姓名/号码/地址不落日志）。
 */
class ContactsSource : SystemSource<ContactData> {

    override val kind: CollectorKind = CollectorKind.CONTACT

    override fun query(
        resolver: ContentResolver,
        cursor: CollectorCursor,
        limit: Int,
    ): List<RawEntry<ContactData>> {
        // 阶段 1：增量取聚合联系人骨架（id / 时间 / 显示名 / lookup_key）。
        val skeletons = ArrayList<Quartet>(limit)
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            CONTACTS_PROJECTION,
            cursorQueryBundle(
                cursor, limit,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                ContactsContract.Contacts._ID,
            ),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.longOrNull(ContactsContract.Contacts._ID) ?: continue
                // 部分 OEM 不维护该列：为 null 时按 0 处理（首轮全量时仍会被采到，
                // 后续增量依赖系统正确维护，属 spec 明示边界）
                val ts = c.longOrNull(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP) ?: 0L
                skeletons += Quartet(
                    id = id,
                    ts = ts,
                    display = c.stringOrNull(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY).orEmpty(),
                    lookup = c.stringOrNull(ContactsContract.Contacts.LOOKUP_KEY),
                )
            }
        }

        // 阶段 2：逐联系人取明细行并组装 DTO。
        return skeletons.map { s ->
            RawEntry(
                systemId = s.id,
                timestamp = s.ts,
                data = loadDetail(resolver, s),
            )
        }
    }

    /** 查某联系人的全部明细行并聚合（行级异常列一律按缺失处理）。 */
    private fun loadDetail(
        resolver: ContentResolver,
        skeleton: Quartet,
    ): ContactData {
        val (contactId, ts, displayName, lookupKey) = skeleton

        var name: ContactName? = null
        var structuredDisplay: String? = null
        var organization: String? = null
        var jobTitle: String? = null
        var birthday: String? = null
        var notes: String? = null
        val phones = ArrayList<ContactPhone>()
        val emails = ArrayList<ContactEmail>()
        val addresses = ArrayList<ContactAddress>()

        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            DATA_PROJECTION,
            DATA_SELECTION,
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                when (c.stringOrNull(ContactsContract.Data.MIMETYPE)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        structuredDisplay = c.stringOrNull(ContactsContract.Data.DATA1)
                        name = ContactName(
                            given = c.stringOrNull(ContactsContract.Data.DATA2),
                            family = c.stringOrNull(ContactsContract.Data.DATA3),
                            prefix = c.stringOrNull(ContactsContract.Data.DATA4),
                            middle = c.stringOrNull(ContactsContract.Data.DATA5),
                            suffix = c.stringOrNull(ContactsContract.Data.DATA6),
                        )
                    }

                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val number = c.stringOrNull(ContactsContract.Data.DATA1) ?: continue
                        val typeCode = c.intOrDefault(ContactsContract.Data.DATA2)
                        val label = c.stringOrNull(ContactsContract.Data.DATA3)
                        phones += ContactPhone(
                            number = number,
                            type = TypeMaps.contactType(typeCode, label),
                            label = if (typeCode == 0) label else null,
                            isPrimary = c.intOrDefault(ContactsContract.Data.IS_PRIMARY) == 1,
                        )
                    }

                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val email = c.stringOrNull(ContactsContract.Data.DATA1) ?: continue
                        val typeCode = c.intOrDefault(ContactsContract.Data.DATA2)
                        val label = c.stringOrNull(ContactsContract.Data.DATA3)
                        emails += ContactEmail(
                            email = email,
                            type = TypeMaps.contactType(typeCode, label),
                            label = if (typeCode == 0) label else null,
                            isPrimary = c.intOrDefault(ContactsContract.Data.IS_PRIMARY) == 1,
                        )
                    }

                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val formatted = c.stringOrNull(ContactsContract.Data.DATA1) ?: continue
                        val typeCode = c.intOrDefault(ContactsContract.Data.DATA2)
                        val label = c.stringOrNull(ContactsContract.Data.DATA3)
                        addresses += ContactAddress(
                            formatted = formatted,
                            type = TypeMaps.contactType(typeCode, label),
                            label = if (typeCode == 0) label else null,
                            isPrimary = c.intOrDefault(ContactsContract.Data.IS_PRIMARY) == 1,
                        )
                    }

                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        organization = c.stringOrNull(ContactsContract.Data.DATA1)
                        jobTitle = c.stringOrNull(ContactsContract.Data.DATA4)
                    }

                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        // 只保留生日事件（TYPE_BIRTHDAY = 3），其他事件类型本阶段不采
                        if (c.intOrDefault(ContactsContract.Data.DATA2) ==
                            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
                        ) {
                            birthday = c.stringOrNull(ContactsContract.Data.DATA1)
                        }
                    }

                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        notes = c.stringOrNull(ContactsContract.Data.DATA1)
                    }
                }
            }
        }

        return ContactData(
            displayName = displayName.ifEmpty { structuredDisplay.orEmpty() },
            name = name,
            organization = organization,
            jobTitle = jobTitle,
            phones = phones,
            emails = emails,
            addresses = addresses,
            birthday = birthday,
            notes = notes,
            source = CollectorSource(
                systemId = contactId,
                lookupKey = lookupKey,
                lastUpdated = ts,
            ),
        )
    }

    /** 阶段 1 骨架四元组（id / 时间戳 / 显示名 / lookup_key）。 */
    private data class Quartet(val id: Long, val ts: Long, val display: String, val lookup: String?)

    private companion object {
        val CONTACTS_PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ContactsContract.Contacts.LOOKUP_KEY,
        )

        /** 明细行只取需要的 mimetype，避免无关数据（如头像/IM）进内存。 */
        val DATA_SELECTION = """
            ${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (
                '${ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE}',
                '${ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE}',
                '${ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE}',
                '${ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE}',
                '${ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE}',
                '${ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE}',
                '${ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE}'
            )
        """.trimIndent()

        val DATA_PROJECTION = arrayOf(
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.IS_PRIMARY,
        )
    }
}
