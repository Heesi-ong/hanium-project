import unittest

from fastapi import HTTPException

from Back.app.routers.admin import require_admin


class AdminAccessTest(unittest.TestCase):
    def test_regular_user_cannot_access_admin_status(self):
        with self.assertRaises(HTTPException) as raised:
            require_admin({"id": 7, "role": "user"})

        self.assertEqual(raised.exception.status_code, 403)

    def test_admin_can_access_admin_status(self):
        user = {"id": 1, "role": "admin"}
        self.assertEqual(require_admin(user), user)


if __name__ == "__main__":
    unittest.main()
