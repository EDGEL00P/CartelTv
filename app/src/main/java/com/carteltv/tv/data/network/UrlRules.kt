package com.carteltv.tv.data.network

/** True for an absolute http or https URL. Scheme matching is case-insensitive and needs the "://" so "httpx:" fails. */
fun String.isHttpUrl(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
