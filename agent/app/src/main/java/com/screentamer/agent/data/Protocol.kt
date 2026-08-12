package com.screentamer.agent.data

import org.json.JSONObject

/**
 * Wire protocol shared between the agent and the parent server.
 * Message envelope: {"type": "...", ...fields}
 */
object Protocol {
    // Client -> server
    const val TYPE_HELLO = "hello"
    const val TYPE_USAGE = "usage"
    const val TYPE_LOG = "log"

    // Server -> client
    const val TYPE_WELCOME = "welcome"
    const val TYPE_CONFIG = "config"
    const val TYPE_COMMAND = "command"

    // Command types (server -> agent)
    const val CMD_PAUSE = "pause"
    const val CMD_PLAY = "play"
    const val CMD_HOME = "home"
    const val CMD_STOP_APP = "stopApp"
    const val CMD_LOCK = "lock"
    const val CMD_UNLOCK = "unlock"
    const val CMD_CHECK_UPDATE = "checkUpdate"

    fun hello(token: String, deviceId: String, name: String, model: String, version: String, appVersion: String? = null): JSONObject =
        JSONObject()
            .put("type", TYPE_HELLO)
            .put("role", "agent")
            .put("token", token)
            .put("deviceId", deviceId)
            .put("name", name)
            .put("model", model)
            .put("version", version)
            .put("appVersion", appVersion ?: "")

    fun usage(
        deviceId: String,
        date: String,
        apps: Map<String, Long>,
        hourly: Map<String, Map<String, Long>>,
        totalMs: Long,
        currentApp: String?,
        locked: Boolean,
    ): JSONObject = JSONObject()
        .put("type", TYPE_USAGE)
        .put("deviceId", deviceId)
        .put("date", date)
        .put("apps", JSONObject(apps.mapValues { it.value.toString() }))
        .put("hourly", JSONObject(hourly.mapValues { (_, byPkg) ->
            JSONObject(byPkg.mapValues { it.value.toString() })
        }))
        .put("totalMs", totalMs)
        .put("currentApp", currentApp ?: JSONObject.NULL)
        .put("locked", locked)

    fun log(deviceId: String, msg: String): JSONObject = JSONObject()
        .put("type", TYPE_LOG)
        .put("deviceId", deviceId)
        .put("msg", msg)
        .put("ts", System.currentTimeMillis())
}
