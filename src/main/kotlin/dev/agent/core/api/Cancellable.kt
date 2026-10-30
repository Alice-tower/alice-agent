package dev.agent.core.api

/** 可重复调用的取消句柄。应快速返回，不等待业务回调；取消不撤销已发生的外部操作。 */
interface Cancellable { fun cancel() }
