/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests

import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.intrinsics.*
import java.util.concurrent.*
import kotlin.coroutines.*
import kotlin.test.*

@Suppress("KDocMissingDocumentation")
abstract class HttpClientTest(private val factory: HttpClientEngineFactory<*>) : TestWithKtor() {
    override val server: ApplicationEngine = embeddedServer(Netty, serverPort) {
        routing {
            get("/empty") {
                call.respondText("")
            }
            get("/hello") {
                call.respondText("hello")
            }
            post("/contentLengthRequestHeader") {
                call.respondText(call.request.headers["content-length"] ?: "null")
            }
            get("/contentLengthRequestHeader") {
                call.respondText(call.request.headers["content-length"] ?: "null")
            }
        }
    }

    @Test
    fun testContentLengthSetToZeroForPostWithoutBodyBeingSet() {
        runBlocking {
            val client = HttpClient(factory)
            val statement = client.post<HttpStatement>("http://localhost:$serverPort/contentLengthRequestHeader")
            assertEquals("0", statement.execute().readText(), "expect proper content-length to be set")
        }
    }

    @Test
    fun testWithNoParentJob() {
        val block = suspend {
            val client = HttpClient(factory)
            val statement = client.get<HttpStatement>("http://localhost:$serverPort/hello")
            assertEquals("hello", statement.execute().readText())
        }

        val latch = ArrayBlockingQueue<Result<Unit>>(1)

        block.startCoroutine(object : Continuation<Unit> {
            override val context: CoroutineContext
                get() = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                latch.put(result)
            }
        })

        latch.take().exceptionOrNull()?.let { throw it }
    }

    @Test
    fun configCopiesOldFeaturesAndInterceptors() {
        val customFeatureKey = AttributeKey<Boolean>("customFeature")
        val anotherCustomFeatureKey = AttributeKey<Boolean>("anotherCustomFeature")

        val originalClient = HttpClient(factory) {
            useDefaultTransformers = false

            install(DefaultRequest) {
                port = serverPort
                url.path("empty")
            }
            install("customFeature") {
                attributes.put(customFeatureKey, true)
            }
        }

        // check everything was installed in original
        val originalRequest = runBlocking {
            originalClient.request<HttpResponse>(HttpRequestBuilder())
        }.request
        assertEquals("/empty", originalRequest.url.fullPath)

        assertTrue(originalClient.attributes.contains(customFeatureKey), "no custom feature installed")

        // create a new client, copying the original, with:
        // - a reconfigured DefaultRequest
        // - a new custom feature
        val newClient = originalClient.config {
            install(DefaultRequest) {
                port = serverPort
                url.path("hello")
            }
            install("anotherCustomFeature") {
                attributes.put(anotherCustomFeatureKey, true)
            }
        }

        // check the custom feature remained installed
        // and that we override the DefaultRequest
        val newRequest = runBlocking {
            newClient.request<HttpResponse>(HttpRequestBuilder())
        }.request
        assertEquals("/hello", newRequest.url.fullPath)

        assertTrue(newClient.attributes.contains(customFeatureKey), "no custom feature installed")

        // check the new custom feature is there too
        assertTrue(newClient.attributes.contains(anotherCustomFeatureKey), "no other custom feature installed")
    }

}
