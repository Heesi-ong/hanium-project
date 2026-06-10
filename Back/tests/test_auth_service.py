import unittest

from Back.app.services.auth_service import hash_password, normalize_email, verify_password


class AuthServiceTests(unittest.TestCase):
    def test_password_hash_round_trip(self):
        stored = hash_password("correct-password")
        self.assertTrue(verify_password("correct-password", stored))
        self.assertFalse(verify_password("wrong-password", stored))
        self.assertNotIn("correct-password", stored)

    def test_normalize_email(self):
        self.assertEqual(normalize_email("  USER@Example.COM "), "user@example.com")


if __name__ == "__main__":
    unittest.main()
