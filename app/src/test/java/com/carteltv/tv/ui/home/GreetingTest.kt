package com.carteltv.tv.ui.home

import com.carteltv.tv.R
import com.carteltv.tv.ui.theme.cartelGreeting
import com.carteltv.tv.ui.theme.cartelGreetingArt
import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun greetingAndArtUseTheSameLocalTimePeriod() {
        listOf(5, 8, 11).forEach { hour ->
            assertEquals("Good morning", cartelGreeting(hour))
            assertEquals(R.drawable.greeting_morning, cartelGreetingArt(hour))
        }
        listOf(12, 15, 16).forEach { hour ->
            assertEquals("Good afternoon", cartelGreeting(hour))
            assertEquals(R.drawable.greeting_afternoon, cartelGreetingArt(hour))
        }
        listOf(17, 19, 21).forEach { hour ->
            assertEquals("Good evening", cartelGreeting(hour))
            assertEquals(R.drawable.greeting_evening, cartelGreetingArt(hour))
        }
        listOf(0, 4, 22, 23).forEach { hour ->
            assertEquals("Good night", cartelGreeting(hour))
            assertEquals(R.drawable.greeting_night, cartelGreetingArt(hour))
        }
    }
}
