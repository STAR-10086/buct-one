package com.buct.course.login

import com.buct.course.model.SchoolConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordLoginGatewayFactoryTest {
    @Test
    fun createsBuctSsoGatewayOnlyForBuct() {
        val buct = SchoolConfig("buct", "北京化工大学", "jwglxt.buct.edu.cn", "https")
        val other = SchoolConfig("other", "Other", "jw.example.edu.cn", "https")

        assertTrue(PasswordLoginGatewayFactory.create(buct) is BuctSsoLoginManager)
        assertTrue(PasswordLoginGatewayFactory.create(other) is PasswordLoginManager)
    }
}
