package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CigramTextTest {

    @Test public void normalizeUnifiesArabicSpellingVariants() {
        assertEquals(CigramText.normalize("أحمد"), CigramText.normalize("احمد"));
        assertEquals(CigramText.normalize("مَدْرَسَة"), CigramText.normalize("مدرسه"));
        assertEquals(CigramText.normalize("على"), CigramText.normalize("علي"));
        assertEquals("ابو ظبي 2020", CigramText.normalize("أبو-ظبي ٢٠٢٠"));      // punctuation becomes one space
    }

    @Test public void normalizeCollapsesPunctuationAndCase() {
        assertEquals("the lord of the rings", CigramText.normalize("  The Lord, of the... RINGS! "));
        assertEquals("", CigramText.normalize("***"));
        assertEquals("", CigramText.normalize(null));
    }

    @Test public void yearIsFoundAndRemovedOnlyWhenItIsAYear() {
        assertEquals(2021, CigramText.yearIn("Dune 2021"));
        assertEquals(2021, CigramText.yearIn("Dune (2021)"));
        assertEquals(2012, CigramText.yearIn("2012"));          // a title that is only a number: the search code falls back to the whole query
        assertEquals(0, CigramText.yearIn("Apollo 13"));
        assertEquals("Dune", CigramText.stripYear("Dune 2021"));
        assertEquals("Dune", CigramText.stripYear("Dune (2021)"));
        assertEquals("Apollo 13", CigramText.stripYear("Apollo 13"));
    }

    @Test public void rtlDetection() {
        assertTrue(CigramText.mostlyRtl("مرحبا بالعالم"));
        assertFalse(CigramText.mostlyRtl("Hello world"));
        assertTrue(CigramText.mostlyRtl("مرحبا Hello"));
        assertFalse(CigramText.mostlyRtl("123 !!!"));
    }
}
