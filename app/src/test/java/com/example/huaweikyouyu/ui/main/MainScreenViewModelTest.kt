package com.example.huaweikyouyu.ui.main

import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun dumpDataTypeConstants() {
      println("--- DUMPING STATIC FIELDS OF DataType ---")
      try {
          val clazz = Class.forName("com.huawei.hms.hihealth.data.DataType")
          clazz.declaredFields.forEach { field ->
              if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                  try {
                      field.isAccessible = true
                      val value = field.get(null)
                      println("DataType: ${field.name} = $value")
                  } catch (e: Exception) {
                      println("DataType: ${field.name} (Error: ${e.message})")
                  }
              }
          }
      } catch (e: Exception) {
          println("Error: ${e.message}")
      }
      assert(true)
  }
}
