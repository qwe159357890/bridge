package my.bridge.com.automation.automator.filter

import my.bridge.com.automation.automator.UiObject

/**
 * 整型属性过滤器（移植自 autox filter/IntFilter.kt）。
 */
class IntFilter(private val mIntProperty: IntProperty, private val mValue: Int) : Filter {

    interface IntProperty {
        operator fun get(`object`: UiObject): Int
    }

    override fun filter(node: UiObject): Boolean {
        return mIntProperty[node] == mValue
    }

    override fun toString(): String {
        return mIntProperty.toString() + "(" + mValue + ")"
    }

    companion object {

        val DEPTH: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.depth()
            override fun toString(): String = "depth"
        }

        val ROW: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.row()
            override fun toString(): String = "row"
        }

        val ROW_COUNT: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.rowCount()
            override fun toString(): String = "rowCount"
        }

        val ROW_SPAN: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.rowSpan()
            override fun toString(): String = "rowSpan"
        }

        val COLUMN: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.column()
            override fun toString(): String = "column"
        }

        val COLUMN_COUNT: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.columnCount()
            override fun toString(): String = "columnCount"
        }

        val COLUMN_SPAN: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.columnSpan()
            override fun toString(): String = "columnSpan"
        }

        val INDEX_IN_PARENT: IntProperty = object : IntProperty {
            override fun get(`object`: UiObject): Int = `object`.indexInParent()
            override fun toString(): String = "indexInParent"
        }
    }
}
