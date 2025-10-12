package com.github.reygnn.kolibri_launcher

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.matcher.ViewMatchers.assertThat
import org.hamcrest.Matcher
import org.hamcrest.Matchers.`is`

// GEÄNDERT: Der Konstruktor akzeptiert jetzt einen Matcher<Int>
class RecyclerViewItemCountAssertion(private val matcher: Matcher<Int>) : ViewAssertion {

    override fun check(view: View?, noViewFoundException: NoMatchingViewException?) {
        if (noViewFoundException != null) {
            throw noViewFoundException
        }

        val recyclerView = view as RecyclerView
        val adapter = recyclerView.adapter
        // Wir verwenden jetzt den übergebenen Matcher in der Behauptung
        assertThat(adapter!!.itemCount, matcher)
    }

    companion object {
        // NEU: Eine Hilfsfunktion, die es einfacher macht, die Assertion zu erstellen
        fun withItemCount(matcher: Matcher<Int>): RecyclerViewItemCountAssertion {
            return RecyclerViewItemCountAssertion(matcher)
        }

        // Du kannst auch die alte Funktion für exakte Zählungen behalten, falls du sie brauchst
        fun withItemCount(expectedCount: Int): RecyclerViewItemCountAssertion {
            return RecyclerViewItemCountAssertion(`is`(expectedCount))
        }
    }
}