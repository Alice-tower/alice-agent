package dev.agent.core.api

/** 取消通知在触发取消的线程同步执行，应快速返回。 */
interface CancellationListener { fun onCancelled() }
