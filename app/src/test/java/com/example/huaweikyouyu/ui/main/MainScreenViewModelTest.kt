package com.example.huaweikyouyu.ui.main

import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun dumpSettingControllerMethods() {
      println("--- DUMPING METHODS OF SettingController ---")
      try {
          val clazz = Class.forName("com.huawei.hms.hihealth.SettingController")
          clazz.declaredMethods.forEach { method ->
              val params = method.parameterTypes.map { it.name }.joinToString(", ")
              println("Method: ${method.name}($params) -> ${method.returnType.name}")
          }
      } catch (e: Exception) {
          println("Error: ${e.message}")
      }
      assert(true)
  }
}
