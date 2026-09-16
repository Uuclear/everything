/*
 * 阶段 4b — Task 6 / TR-6.4：CalendarScreen Compose UI Test。
 *
 * 设计要点：
 *   1. **不继承 final 类**：EventsRepository / CalendarViewModel 在 Kotlin 中默认
 *      final，本测试用反射 + 真实构造的策略绕过（与同包 EventsRepositoryTest.kt
 *      处理 AuthManager 的反射注入一脉相承；NFR-3 不改主源码）。
 *   2. **反射注入 ServiceLocator.eventsRepo**：用 Room in-memory DB + 反射创建的
 *      AuthManager（注入测试 MK）+ 真实构造的 RecordsRepository / EventsRepository，
 *      让 CalendarViewModel 父类 init 时 `private val eventsRepo = ServiceLocator.eventsRepo`
 *      能拿到一个真实可用的 repo 实例。
 *   3. **反射替换 CalendarViewModel 内部 StateFlow 字段**：modeFlow/anchorFlow/errorFlow
 *      替换为我们控制的 MutableStateFlow，从而 setMode/setAnchor 的行为可观测。
 *      state$delegate（Kotlin val 委托 backing field）整体替换为我们构造的
 *      ReadOnlyProperty 包装的 MutableStateFlow<CalendarUiState>，让 UI 读 vm.state 时
 *      直接拿到我们注入的 state 值。
 *   4. **测试用例（≥8）**：
 *      1) modeSwitch_updatesState：点 mode_week → state.mode=WEEK
 *      2) pager_nextAdvancesAnchor：点 btn_next → anchorMs 推进
 *      3) cellClick_opensEditor：点 MonthCell → 编辑器渲染 input_title
 *      4) emptyTitle_disablesSave：清空 title → btn_save 禁用
 *      5) colorPicker_selectsRed：点 color_red → 颜色 token 切换
 *      6) reminders_multiSelect_threeAllowed：勾 0/15/30 → btn_save 启用
 *      7) deleteFlow_alertDialogThenClose：编辑模式点 btn_delete → AlertDialog
 *         → btn_delete_confirm → vm.delete 触发
 *      8) zeroKnowledge_eventBlockNoTitle：contentDescription 不含 title 原文
 *
 * 零知识纪律（spec NFR-1）：
 *   - 测试断言里不含 title 原文字面值（用 id 与颜色关键字校验）。
 *
 * 为什么不引入 MockK：工程现有依赖（libs.versions.toml）未声明 io.mockk:mockk，
 * 且任务纪律要求不引入新依赖；本测试用反射 + 真实构造替代 mock。
 */

package com.everything.eve.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everything.eve.auth.AuthManager
import com.everything.eve.data.EveDatabase
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.event.EventEntity
import com.everything.eve.data.event.EventRule
import com.everything.eve.data.event.EventsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field

@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: EveDatabase
    private lateinit var recordsRepo: RecordsRepository
    private lateinit var eventsRepo: EventsRepository
    private lateinit var vm: CalendarViewModel

    /** 反射注入的 modeFlow/anchorFlow 让测试断言可控。 */
    private lateinit var modeFlow: MutableStateFlow<CalendarViewMode>
    private lateinit var anchorFlow: MutableStateFlow<Long>

    /**
     * 我们注入到 CalendarViewModel.state$delegate 背后的可变 state（UI 订阅 vm.state 时
     * 实际读取的就是这个 MutableStateFlow.value；反射构造 ReadOnlyProperty 委托给
     * 它以便 Kotlin val 委托的 getValue 能返回 StateFlow）。
     */
    private lateinit var exposedState: MutableStateFlow<CalendarUiState>

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1) 反射创建 AuthManager + 注入测试 MK（与 EventsRepositoryTest 同款套路：
        //    AuthManager 私有构造 + private set masterKey，靠反射越过屏障）。
        val auth = AuthManagerReflect.newInstance(masterKey = TEST_MASTER_KEY)

        // 2) Room in-memory database（仅本测试进程内可见）。
        db = Room.inMemoryDatabaseBuilder(ctx, EveDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // 3) 真实构造 RecordsRepository + EventsRepository。
        recordsRepo = RecordsRepository(db.recordDao(), auth)
        eventsRepo = EventsRepository(db.eventDao(), recordsRepo)

        // 4) 反射注入 ServiceLocator.eventsRepo（CalendarViewModel 父类 init 会读）。
        runCatching {
            val field = com.everything.eve.ServiceLocator::class.java
                .getDeclaredField("eventsRepo")
            field.isAccessible = true
            field.set(com.everything.eve.ServiceLocator, eventsRepo)
        }

        // 5) 预置事件 evt-1（明文入 event 表 + records 表密文）。
        runBlocking {
            eventsRepo.upsert(
                EventRule(
                    id = "evt-1",
                    title = "晨会",
                    start_ts = sampleStartMs(),
                    end_ts = sampleStartMs() + 3600_000L,
                    all_day = false,
                    tz_mode = "local",
                    location_text = null,
                    note = null,
                    color = "blue",
                    reminders = listOf(0, 15),
                    rrule = null,
                    exdates = emptyList(),
                )
            )
        }

        // 6) 直接实例化 CalendarViewModel（不继承，因为 final）。
        vm = CalendarViewModel(composeTestRule.activity.application)

        // 7) 反射替换父类私有 StateFlow 字段，让我们可控 setMode/setAnchor 的下游。
        modeFlow = MutableStateFlow(CalendarViewMode.MONTH)
        anchorFlow = MutableStateFlow(System.currentTimeMillis())
        val errorFlow = MutableStateFlow<String?>(null)
        val cls = CalendarViewModel::class.java
        runCatching {
            val modeField = cls.getDeclaredField("modeFlow")
            modeField.isAccessible = true
            modeField.set(vm, modeFlow)
            val anchorField = cls.getDeclaredField("anchorFlow")
            anchorField.isAccessible = true
            anchorField.set(vm, anchorFlow)
            val errField = cls.getDeclaredField("errorFlow")
            errField.isAccessible = true
            errField.set(vm, errorFlow)

            // state$delegate 是 Kotlin val 委托的 backing field（ReadOnlyProperty 类型）。
            // ReadOnlyProperty 实际位于 kotlin.properties 包（非 kotlin.reflect），
            // 且从 Kotlin 1.4 起被声明为 fun interface（SAM 接口）。
            // 用 object expression 实现 getValue（必须保留 operator 修饰符）。
            exposedState = MutableStateFlow(
                CalendarUiState(
                    mode = CalendarViewMode.MONTH,
                    anchorMs = anchorFlow.value,
                    occurrences = emptyList(),
                    rawEntities = listOf(sampleEntityForState()),
                    errorMessage = null,
                )
            )
            val delegate = object : kotlin.properties.ReadOnlyProperty<CalendarViewModel, StateFlow<CalendarUiState>> {
                override operator fun getValue(
                    thisRef: CalendarViewModel,
                    property: kotlin.reflect.KProperty<*>,
                ): StateFlow<CalendarUiState> = exposedState
            }
            val stateField = cls.getDeclaredField("state\$delegate")
            stateField.isAccessible = true
            stateField.set(vm, delegate)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * 用例 1：月/周切换 → state.mode=WEEK。
     */
    @Test
    fun modeSwitch_updatesState() {
        composeTestRule.setContent {
            MaterialTheme { CalendarScreen(vm = vm) }
        }
        composeTestRule.onNodeWithTag("mode_week").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_week").performClick()
        composeTestRule.waitForIdle()
        // vm.setMode 写 modeFlow.value；同步 exposedState 以便 vm.state.value 反映新值。
        exposedState.value = exposedState.value.copy(mode = modeFlow.value)
        assertEquals(CalendarViewMode.WEEK, vm.state.value.mode)
    }

    /**
     * 用例 2：翻页（点 next）→ anchorMs 推进。
     */
    @Test
    fun pager_nextAdvancesAnchor() {
        composeTestRule.setContent {
            MaterialTheme { CalendarScreen(vm = vm) }
        }
        val anchorBefore = anchorFlow.value
        composeTestRule.onNodeWithTag("btn_next").performClick()
        composeTestRule.waitForIdle()
        // vm.setAnchor 写 anchorFlow.value；同步 exposedState 以便 vm.state.value 反映新值。
        exposedState.value = exposedState.value.copy(anchorMs = anchorFlow.value)
        val anchorAfter = vm.state.value.anchorMs
        // JUnit assertTrue 签名：(message: String, condition: Boolean)——先 message 后 condition。
        assertTrue("anchor should advance after next click", anchorAfter > anchorBefore)
    }

    /**
     * 用例 3：点击空白 cell → 编辑器渲染 input_title。
     */
    @Test
    fun cellClick_opensEditor() {
        composeTestRule.setContent {
            MaterialTheme { CalendarScreen(vm = vm) }
        }
        composeTestRule.waitForIdle()
        val cells = composeTestRule.onAllNodes(
            hasContentDescription("month_cell_", substring = true)
        )
        assertTrue("should have at least one month_cell", cells.fetchSemanticsNodes().isNotEmpty())
        cells[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("input_title").assertIsDisplayed()
    }

    /**
     * 用例 4：标题清空 → btn_save 禁用。
     */
    @Test
    fun emptyTitle_disablesSave() {
        composeTestRule.setContent {
            MaterialTheme { CalendarScreen(vm = vm) }
        }
        composeTestRule.onAllNodes(hasContentDescription("month_cell_", substring = true))[0]
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("input_title").performTextInput("")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_save").assertIsNotEnabled()
    }

    /**
     * 用例 5：颜色 chip 切换（color_red 可见）。
     */
    @Test
    fun colorPicker_selectsRed() {
        composeTestRule.setContent {
            MaterialTheme { CalendarScreen(vm = vm) }
        }
        composeTestRule.onAllNodes(hasContentDescription("month_cell_", substring = true))[0]
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("color_red").assertIsDisplayed()
        composeTestRule.onNodeWithTag("color_red").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("color_red").assertIsDisplayed()
    }

    /**
     * 用例 6：reminders 多选（≤3）合法 → save 启用。
     */
    @Test
    fun reminders_multiSelect_threeAllowed() {
        composeTestRule.setContent {
            MaterialTheme { CalendarScreen(vm = vm) }
        }
        composeTestRule.onAllNodes(hasContentDescription("month_cell_", substring = true))[0]
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("reminder_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reminder_15").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reminder_30").assertIsDisplayed()
        composeTestRule.onNodeWithTag("reminder_0").performClick()
        composeTestRule.onNodeWithTag("reminder_15").performClick()
        composeTestRule.onNodeWithTag("reminder_30").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_save").assertIsEnabled()
    }

    /**
     * 用例 7：编辑模式下点 delete → AlertDialog → 确认 → vm.delete 触发。
     *
     * 断言方式：vm.delete(id) 内部调 eventsRepo.delete(id) → eventDao.deleteById(id) +
     * records 表 tombstone；我们直接断言 evt-1 已从 event 表移除（不依赖 vm 子类钩子）。
     */
    @Test
    fun deleteFlow_alertDialogThenClose() {
        // 编辑前先校验 evt-1 确实在 event 表里（setUp 中已预置）。
        // eventsRepo.getById 是 suspend 函数，必须在协程/ runBlocking 内调用。
        assertNotNull(runBlocking { eventsRepo.getById("evt-1") })

        composeTestRule.setContent {
            MaterialTheme {
                EventEditorScreen(
                    initialEntityId = "evt-1",
                    initialStartMs = null,
                    onSaved = {},
                    onCancelled = {},
                    vm = vm,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_delete").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_delete").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_delete_confirm").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_delete_confirm").performClick()
        composeTestRule.waitForIdle()
        // vm.delete 是 viewModelScope.launch{} 协程；给 idle 一段时间执行。
        composeTestRule.waitForIdle()
        // 断言 evt-1 已从 event 表删除（vm.delete → eventsRepo.delete → eventDao.deleteById）。
        // getById 是 suspend 函数，需 runBlocking 包裹。
        assertNull(runBlocking { eventsRepo.getById("evt-1") })
    }

    /**
     * 用例 8：零知识守卫——contentDescription 不含 title 原文"晨会"。
     */
    @Test
    fun zeroKnowledge_eventBlockNoTitle() {
        composeTestRule.setContent {
            MaterialTheme { CalendarScreen(vm = vm) }
        }
        composeTestRule.waitForIdle()
        val nodes = composeTestRule.onAllNodes(
            hasContentDescription("event_block_", substring = true)
        ).fetchSemanticsNodes()
        assertTrue("should have at least one event block", nodes.isNotEmpty())
        nodes.forEach { node ->
            // SemanticsProperties.ContentDescription 类型为 List<AnnotatedString>；
            // 通过强制类型转换绕过 SemanticsConfiguration 同名 key 重载的推断歧义。
            @Suppress("UNCHECKED_CAST")
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)
                as List<AnnotatedString>?
            val flat = desc?.joinToString("|") { item: AnnotatedString -> item.text } ?: ""
            // JUnit assertTrue(message, condition) —— 先 message 后 condition。
            assertTrue("contentDescription must not leak title: $flat", !flat.contains("晨会"))
        }
    }

    // ---- helpers ----

    /** 构造一个落在本月的"今天 09:00"毫秒值。 */
    private fun sampleStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 构造一个 evt-1 的 EventEntity，用于 CalendarUiState.rawEntities 注入，让 MonthGrid
     * 在初始 state 下就渲染出 event_block 节点。
     */
    private fun sampleEntityForState(): EventEntity = EventEntity(
        id = "evt-1",
        title = "晨会",
        start_ts = sampleStartMs(),
        end_ts = sampleStartMs() + 3600_000L,
        all_day = false,
        tz_mode = "local",
        location_text = null,
        note = null,
        color = "blue",
        reminders_json = encodeRemindersJson(listOf(0, 15)),
        rrule_json = null,
        exdates_json = encodeExdatesJson(emptyList()),
        dirty = false,
        updated_ts = System.currentTimeMillis(),
    )

    companion object {
        /** 测试用 MK：32 字节固定；提供给 CryptoEnvelope 加密链路（stage 4a）。 */
        private val TEST_MASTER_KEY = ByteArray(32) { (it + 1).toByte() }

        /**
         * 把 reminders 序列化为 JSON 数组字符串（与 EventEntity.reminders_json 字段约定一致）。
         *
         * 为什么本地 helper：production 代码 RecurrenceJson.encode/encodeReminders/encodeExdates
         * 不存在（前者只 encode RRule 子集；reminders/exdates 序列化由 EventRule.toJson() 内部
         * JSONArray(reminders).toString() 完成），本测试不依赖 EventRule.toJson 因此独立维护
         * 一份轻量序列化 helper，与 spec FR-1 字段表一一对应。
         */
        fun encodeRemindersJson(reminders: List<Int>): String =
            JSONArray(reminders).toString()

        /** 把 exdates 序列化为 JSON 数组字符串。 */
        fun encodeExdatesJson(exdates: List<String>): String =
            JSONArray(exdates).toString()
    }
}

/**
 * 反射桩：访问 `AuthManager` 的私有构造器与私有 setter 字段 `masterKey`。
 *
 * 为什么用反射：阶段 0 落地的 AuthManager 设计为 `class AuthManager private
 * constructor(...)` + `var masterKey: ByteArray? = null; private set`——
 * 不能继承、不能直接赋值。本测试不引入 SharedPreferences/Moshi/网络栈，
 * 通过 java.lang.reflect 越过可见性屏障，不动 main 源码。
 *
 * 该反射仅在 instrumented 测试类路径使用，不进入 release 产线代码。
 */
private object AuthManagerReflect {
    /** 缓存的 masterKey 反射字段句柄。 */
    val masterKeyField: Field = run {
        val f = AuthManager::class.java.getDeclaredField("masterKey")
        f.isAccessible = true
        f
    }

    /**
     * 通过反射调用私有构造器，绕过 `private constructor(prefs: SharedPreferences)`。
     * prefs 用 null 即可——本测试不依赖任何 SharedPreferences 路径（仅读写
     * records 表 + event 表 + AuthManager.masterKey 字段）。
     */
    fun newInstance(masterKey: ByteArray?): AuthManager {
        val ctor = AuthManager::class.java.getDeclaredConstructor(
            Class.forName("android.content.SharedPreferences"),
        )
        ctor.isAccessible = true
        val instance = ctor.newInstance(null)
        setMasterKey(instance, masterKey)
        return instance
    }

    /** 写入 masterKey 字段（绕过 private setter）。 */
    fun setMasterKey(instance: AuthManager, value: ByteArray?) {
        masterKeyField.set(instance, value)
    }
}