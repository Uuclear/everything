// 顶栏搜索框与当前列表页共享的搜索词（模块级单例 reactive）。
import { reactive } from 'vue'

export const uiSearch = reactive({
  query: '',
})
