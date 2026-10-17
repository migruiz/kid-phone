package ovh.tenjo.kidphone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ovh.tenjo.kidphone.data.PhoneNumbers

class PhoneNumbersTest {
    @Test fun nationalAndInternationalFormsMatch() {
        assertTrue(PhoneNumbers.same("087 123 4567", "+353871234567"))
        assertTrue(PhoneNumbers.same("(087) 123-4567", "00353 87 123 4567"))
        assertTrue(PhoneNumbers.same("01 234 5678", "+35312345678"))
        assertTrue(PhoneNumbers.same("021 123 4567", "+353211234567"))
        assertTrue(PhoneNumbers.same("0871234567", "871234567"))
    }

    @Test fun sameDigitsInAnotherCountryDoNotMatch() {
        assertFalse(PhoneNumbers.same("+353 87 123 4567", "+34 87 123 4568"))
        assertFalse(PhoneNumbers.same("+353 1 234 5678", "+44 20 1234 5678"))
    }

    @Test fun differentNumbersDoNotMatch() {
        assertFalse(PhoneNumbers.same("087 123 4567", "087 123 4568"))
        assertFalse(PhoneNumbers.same("086 123 4567", "087 123 4567"))
    }

    @Test fun withheldNumbersNeverMatch() {
        assertFalse(PhoneNumbers.same("", "087 123 4567"))
        assertFalse(PhoneNumbers.same(null, "087 123 4567"))
        assertFalse(PhoneNumbers.same("", ""))
    }

    @Test fun shortNumbersMustMatchExactly() {
        assertTrue(PhoneNumbers.same("50000", "50000"))
        assertFalse(PhoneNumbers.same("50000", "150000"))
    }
}
