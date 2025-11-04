package com.example.visiontest.common

data class MobileDevice (
    val id: String,
    val name: String,
    val type: DeviceType,
    val state: String,
    val osVersion: String? = null,
    val modelName: String? = null
)