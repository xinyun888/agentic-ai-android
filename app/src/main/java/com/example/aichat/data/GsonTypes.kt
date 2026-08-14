package com.example.aichat.data

import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * R8 混淆会剥离匿名类（TypeToken<...>() {}）的泛型签名，
 * 导致 release 版反序列化时抛 "Missing type parameter"。
 * 这里用 getParameterized 在运行时显式构造泛型 Type，不依赖签名属性。
 */
object GsonTypes {
    fun list(element: Type): Type =
        TypeToken.getParameterized(MutableList::class.java, element).type

    fun map(key: Type, value: Type): Type =
        TypeToken.getParameterized(MutableMap::class.java, key, value).type

    /** Map<String, Any> */
    val stringAnyMap: Type by lazy { map(String::class.java, Any::class.java) }

    /** Map<String, String> */
    val stringStringMap: Type by lazy { map(String::class.java, String::class.java) }
}
