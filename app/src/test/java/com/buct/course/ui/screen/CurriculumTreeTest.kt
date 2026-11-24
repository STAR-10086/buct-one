package com.buct.course.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurriculumTreeTest {

    private fun node(
        id: String,
        parentId: String,
        name: String,
        requiredCredits: Double = 10.0,
        earnedCredits: Double = 0.0,
        isLeaf: Boolean = false,
        courses: List<CurriculumCourseUi> = emptyList()
    ) = CurriculumNodeUi(
        id = id,
        parentId = parentId,
        name = name,
        earnedCredits = earnedCredits,
        requiredCredits = requiredCredits,
        passed = earnedCredits >= requiredCredits && requiredCredits > 0.0,
        isLeaf = isLeaf,
        courses = courses
    )

    private fun course(name: String) = CurriculumCourseUi(
        courseCode = "C001",
        courseName = name,
        credits = "2.0",
        studyStatus = "3"
    )

    @Test
    fun buildVisibleCurriculumTree_preservesHierarchyAndConnectorFlags() {
        val nodes = listOf(
            node("root", "", "计算机科学与技术培养方案", requiredCredits = 160.0),
            node("cat1", "root", "通识教育课程", requiredCredits = 40.0),
            node("sub1", "cat1", "思政模块", requiredCredits = 16.0, isLeaf = true, courses = listOf(course("毛概"))),
            node("sub2", "cat1", "外语模块", requiredCredits = 12.0, isLeaf = true),
            node("cat2", "root", "专业核心课", requiredCredits = 60.0, isLeaf = true, courses = listOf(course("数据结构"))),
            node("emptyPlaceholder", "root", "空占位", requiredCredits = 0.0, isLeaf = false)
        )

        val defaultExpanded = defaultExpandedCurriculumIds(nodes)
        assertEquals(setOf("cat1"), defaultExpanded)

        val allExpandable = collectExpandableCurriculumIds(nodes)
        assertEquals(setOf("cat1", "sub1", "cat2"), allExpandable)

        val entries = buildVisibleCurriculumTree(nodes, defaultExpanded)
        assertEquals(listOf("cat1", "sub1", "sub2", "cat2"), entries.map { it.node.id })

        assertEquals(0, entries[0].depth)
        assertEquals(2, entries[0].childCount)
        assertTrue(entries[0].isExpandable)
        assertTrue(entries[0].isExpanded)
        assertFalse(entries[0].isLastChild)

        assertEquals(1, entries[1].depth)
        assertTrue(entries[1].isExpandable)
        assertFalse(entries[1].isExpanded)
        assertFalse(entries[1].isLastChild)
        assertEquals(emptyList<Boolean>(), entries[1].ancestorHasNext)

        assertEquals(1, entries[2].depth)
        assertFalse(entries[2].isExpandable)
        assertTrue(entries[2].isLastChild)

        assertEquals(0, entries[3].depth)
        assertTrue(entries[3].isExpandable)
        assertTrue(entries[3].isLastChild)
    }

    @Test
    fun buildVisibleCurriculumTree_collapsingParentHidesChildrenAndHandlesCycles() {
        val nodes = listOf(
            node("root", "", "培养方案"),
            node("cat1", "root", "大类A"),
            node("sub1", "cat1", "子类A1", isLeaf = true),
            node("cyc1", "cyc2", "环节点1", isLeaf = true),
            node("cyc2", "cyc1", "环节点2", isLeaf = true)
        )

        val collapsed = buildVisibleCurriculumTree(nodes, emptySet())
        assertEquals(listOf("cat1"), collapsed.map { it.node.id })
        assertFalse(collapsed[0].isExpanded)
        assertTrue(collapsed[0].isExpandable)

        val threeLevel = listOf(
            node("root", "", "方案"),
            node("a", "root", "一级"),
            node("b", "a", "二级"),
            node("c", "b", "三级", isLeaf = true),
            node("a2", "root", "一级二", isLeaf = true)
        )
        val expandedAll = buildVisibleCurriculumTree(threeLevel, setOf("a", "b"))
        assertEquals(listOf("a", "b", "c", "a2"), expandedAll.map { it.node.id })
        assertEquals(2, expandedAll[2].depth)
        assertEquals(listOf(true), expandedAll[2].ancestorHasNext)
    }
}
