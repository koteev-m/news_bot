package app

import auth.WebAppVerifyTest
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite

@Suite
@SelectPackages("routes")
@SelectClasses(
    AppWiringSmokeTest::class,
    WebAppVerifyTest::class,
)
class AllP05RoutesTestSuite
