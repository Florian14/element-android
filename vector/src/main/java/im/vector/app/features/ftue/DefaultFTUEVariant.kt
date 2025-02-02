/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.ftue

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.extensions.POP_BACK_STACK_EXCLUSIVE
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.extensions.addFragmentToBackstack
import im.vector.app.core.extensions.exhaustive
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivityLoginBinding
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.login.LoginAction
import im.vector.app.features.login.LoginCaptchaFragment
import im.vector.app.features.login.LoginCaptchaFragmentArgument
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.login.LoginFragment
import im.vector.app.features.login.LoginGenericTextInputFormFragment
import im.vector.app.features.login.LoginGenericTextInputFormFragmentArgument
import im.vector.app.features.login.LoginMode
import im.vector.app.features.login.LoginResetPasswordFragment
import im.vector.app.features.login.LoginResetPasswordMailConfirmationFragment
import im.vector.app.features.login.LoginResetPasswordSuccessFragment
import im.vector.app.features.login.LoginServerSelectionFragment
import im.vector.app.features.login.LoginServerUrlFormFragment
import im.vector.app.features.login.LoginSignUpSignInSelectionFragment
import im.vector.app.features.login.LoginSplashFragment
import im.vector.app.features.login.LoginViewEvents
import im.vector.app.features.login.LoginViewModel
import im.vector.app.features.login.LoginViewState
import im.vector.app.features.login.LoginWaitForEmailFragment
import im.vector.app.features.login.LoginWaitForEmailFragmentArgument
import im.vector.app.features.login.LoginWebFragment
import im.vector.app.features.login.ServerType
import im.vector.app.features.login.SignMode
import im.vector.app.features.login.TextInputFormFragmentMode
import im.vector.app.features.login.isSupported
import im.vector.app.features.login.terms.LoginTermsFragment
import im.vector.app.features.login.terms.LoginTermsFragmentArgument
import im.vector.app.features.login.terms.toLocalizedLoginTerms
import org.matrix.android.sdk.api.auth.registration.FlowResult
import org.matrix.android.sdk.api.auth.registration.Stage
import org.matrix.android.sdk.api.extensions.tryOrNull

private const val FRAGMENT_REGISTRATION_STAGE_TAG = "FRAGMENT_REGISTRATION_STAGE_TAG"
private const val FRAGMENT_LOGIN_TAG = "FRAGMENT_LOGIN_TAG"

class DefaultFTUEVariant(
        private val views: ActivityLoginBinding,
        private val loginViewModel: LoginViewModel,
        private val activity: VectorBaseActivity<ActivityLoginBinding>,
        private val supportFragmentManager: FragmentManager
) : FTUEVariant {

    private val enterAnim = R.anim.enter_fade_in
    private val exitAnim = R.anim.exit_fade_out

    private val popEnterAnim = R.anim.no_anim
    private val popExitAnim = R.anim.exit_fade_out

    private val topFragment: Fragment?
        get() = supportFragmentManager.findFragmentById(views.loginFragmentContainer.id)

    private val commonOption: (FragmentTransaction) -> Unit = { ft ->
        // Find the loginLogo on the current Fragment, this should not return null
        (topFragment?.view as? ViewGroup)
                // Find findViewById does not work, I do not know why
                // findViewById<View?>(R.id.loginLogo)
                ?.children
                ?.firstOrNull { it.id == R.id.loginLogo }
                ?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
        ft.setCustomAnimations(enterAnim, exitAnim, popEnterAnim, popExitAnim)
    }

    override fun initUiAndData(isFirstCreation: Boolean) {
        if (isFirstCreation) {
            addFirstFragment()
        }

        with(activity) {
            loginViewModel.onEach {
                updateWithState(it)
            }
            loginViewModel.observeViewEvents { handleLoginViewEvents(it) }
        }

        // Get config extra
        val loginConfig = activity.intent.getParcelableExtra<LoginConfig?>(FTUEActivity.EXTRA_CONFIG)
        if (isFirstCreation) {
            loginViewModel.handle(LoginAction.InitWith(loginConfig))
        }
    }

    override fun setIsLoading(isLoading: Boolean) {
        // do nothing
    }

    private fun addFirstFragment() {
        activity.addFragment(views.loginFragmentContainer, LoginSplashFragment::class.java)
    }

    private fun handleLoginViewEvents(loginViewEvents: LoginViewEvents) {
        when (loginViewEvents) {
            is LoginViewEvents.RegistrationFlowResult                     -> {
                // Check that all flows are supported by the application
                if (loginViewEvents.flowResult.missingStages.any { !it.isSupported() }) {
                    // Display a popup to propose use web fallback
                    onRegistrationStageNotSupported()
                } else {
                    if (loginViewEvents.isRegistrationStarted) {
                        // Go on with registration flow
                        handleRegistrationNavigation(loginViewEvents.flowResult)
                    } else {
                        // First ask for login and password
                        // I add a tag to indicate that this fragment is a registration stage.
                        // This way it will be automatically popped in when starting the next registration stage
                        activity.addFragmentToBackstack(views.loginFragmentContainer,
                                LoginFragment::class.java,
                                tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                                option = commonOption
                        )
                    }
                }
            }
            is LoginViewEvents.OutdatedHomeserver                         -> {
                MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.login_error_outdated_homeserver_title)
                        .setMessage(R.string.login_error_outdated_homeserver_warning_content)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                Unit
            }
            is LoginViewEvents.OpenServerSelection                        ->
                activity.addFragmentToBackstack(views.loginFragmentContainer,
                        LoginServerSelectionFragment::class.java,
                        option = { ft ->
                            activity.findViewById<View?>(R.id.loginSplashLogo)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // Disable transition of text
                            // findViewById<View?>(R.id.loginSplashTitle)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // No transition here now actually
                            // findViewById<View?>(R.id.loginSplashSubmit)?.let { ft.addSharedElement(it, ViewCompat.getTransitionName(it) ?: "") }
                            // TODO Disabled because it provokes a flickering
                            // ft.setCustomAnimations(enterAnim, exitAnim, popEnterAnim, popExitAnim)
                        })
            is LoginViewEvents.OnServerSelectionDone                      -> onServerSelectionDone(loginViewEvents)
            is LoginViewEvents.OnSignModeSelected                         -> onSignModeSelected(loginViewEvents)
            is LoginViewEvents.OnLoginFlowRetrieved                       ->
                activity.addFragmentToBackstack(views.loginFragmentContainer,
                        LoginSignUpSignInSelectionFragment::class.java,
                        option = commonOption)
            is LoginViewEvents.OnWebLoginError                            -> onWebLoginError(loginViewEvents)
            is LoginViewEvents.OnForgetPasswordClicked                    ->
                activity.addFragmentToBackstack(views.loginFragmentContainer,
                        LoginResetPasswordFragment::class.java,
                        option = commonOption)
            is LoginViewEvents.OnResetPasswordSendThreePidDone            -> {
                supportFragmentManager.popBackStack(FRAGMENT_LOGIN_TAG, POP_BACK_STACK_EXCLUSIVE)
                activity.addFragmentToBackstack(views.loginFragmentContainer,
                        LoginResetPasswordMailConfirmationFragment::class.java,
                        option = commonOption)
            }
            is LoginViewEvents.OnResetPasswordMailConfirmationSuccess     -> {
                supportFragmentManager.popBackStack(FRAGMENT_LOGIN_TAG, POP_BACK_STACK_EXCLUSIVE)
                activity.addFragmentToBackstack(views.loginFragmentContainer,
                        LoginResetPasswordSuccessFragment::class.java,
                        option = commonOption)
            }
            is LoginViewEvents.OnResetPasswordMailConfirmationSuccessDone -> {
                // Go back to the login fragment
                supportFragmentManager.popBackStack(FRAGMENT_LOGIN_TAG, POP_BACK_STACK_EXCLUSIVE)
            }
            is LoginViewEvents.OnSendEmailSuccess                         -> {
                // Pop the enter email Fragment
                supportFragmentManager.popBackStack(FRAGMENT_REGISTRATION_STAGE_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                activity.addFragmentToBackstack(views.loginFragmentContainer,
                        LoginWaitForEmailFragment::class.java,
                        LoginWaitForEmailFragmentArgument(loginViewEvents.email),
                        tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                        option = commonOption)
            }
            is LoginViewEvents.OnSendMsisdnSuccess                        -> {
                // Pop the enter Msisdn Fragment
                supportFragmentManager.popBackStack(FRAGMENT_REGISTRATION_STAGE_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                activity.addFragmentToBackstack(views.loginFragmentContainer,
                        LoginGenericTextInputFormFragment::class.java,
                        LoginGenericTextInputFormFragmentArgument(TextInputFormFragmentMode.ConfirmMsisdn, true, loginViewEvents.msisdn),
                        tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                        option = commonOption)
            }
            is LoginViewEvents.Failure,
            is LoginViewEvents.Loading                                    ->
                // This is handled by the Fragments
                Unit
        }.exhaustive
    }

    private fun updateWithState(loginViewState: LoginViewState) {
        if (loginViewState.isUserLogged()) {
            val intent = HomeActivity.newIntent(
                    activity,
                    accountCreation = loginViewState.signMode == SignMode.SignUp
            )
            activity.startActivity(intent)
            activity.finish()
            return
        }

        // Loading
        views.loginLoading.isVisible = loginViewState.isLoading()
    }

    private fun onWebLoginError(onWebLoginError: LoginViewEvents.OnWebLoginError) {
        // Pop the backstack
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // And inform the user
        MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_title_error)
                .setMessage(activity.getString(R.string.login_sso_error_message, onWebLoginError.description, onWebLoginError.errorCode))
                .setPositiveButton(R.string.ok, null)
                .show()
    }

    private fun onServerSelectionDone(loginViewEvents: LoginViewEvents.OnServerSelectionDone) {
        when (loginViewEvents.serverType) {
            ServerType.MatrixOrg -> Unit // In this case, we wait for the login flow
            ServerType.EMS,
            ServerType.Other     -> activity.addFragmentToBackstack(views.loginFragmentContainer,
                    LoginServerUrlFormFragment::class.java,
                    option = commonOption)
            ServerType.Unknown   -> Unit /* Should not happen */
        }
    }

    private fun onSignModeSelected(loginViewEvents: LoginViewEvents.OnSignModeSelected) = withState(loginViewModel) { state ->
        // state.signMode could not be ready yet. So use value from the ViewEvent
        when (loginViewEvents.signMode) {
            SignMode.Unknown            -> error("Sign mode has to be set before calling this method")
            SignMode.SignUp             -> {
                // This is managed by the LoginViewEvents
            }
            SignMode.SignIn             -> {
                // It depends on the LoginMode
                when (state.loginMode) {
                    LoginMode.Unknown,
                    is LoginMode.Sso      -> error("Developer error")
                    is LoginMode.SsoAndPassword,
                    LoginMode.Password    -> activity.addFragmentToBackstack(views.loginFragmentContainer,
                            LoginFragment::class.java,
                            tag = FRAGMENT_LOGIN_TAG,
                            option = commonOption)
                    LoginMode.Unsupported -> onLoginModeNotSupported(state.loginModeSupportedTypes)
                }.exhaustive
            }
            SignMode.SignInWithMatrixId -> activity.addFragmentToBackstack(views.loginFragmentContainer,
                    LoginFragment::class.java,
                    tag = FRAGMENT_LOGIN_TAG,
                    option = commonOption)
        }.exhaustive
    }

    /**
     * Handle the SSO redirection here
     */
    override fun onNewIntent(intent: Intent?) {
        intent?.data
                ?.let { tryOrNull { it.getQueryParameter("loginToken") } }
                ?.let { loginViewModel.handle(LoginAction.LoginWithToken(it)) }
    }

    private fun onRegistrationStageNotSupported() {
        MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.app_name)
                .setMessage(activity.getString(R.string.login_registration_not_supported))
                .setPositiveButton(R.string.yes) { _, _ ->
                    activity.addFragmentToBackstack(views.loginFragmentContainer,
                            LoginWebFragment::class.java,
                            option = commonOption)
                }
                .setNegativeButton(R.string.no, null)
                .show()
    }

    private fun onLoginModeNotSupported(supportedTypes: List<String>) {
        MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.app_name)
                .setMessage(activity.getString(R.string.login_mode_not_supported, supportedTypes.joinToString { "'$it'" }))
                .setPositiveButton(R.string.yes) { _, _ ->
                    activity.addFragmentToBackstack(views.loginFragmentContainer,
                            LoginWebFragment::class.java,
                            option = commonOption)
                }
                .setNegativeButton(R.string.no, null)
                .show()
    }

    private fun handleRegistrationNavigation(flowResult: FlowResult) {
        // Complete all mandatory stages first
        val mandatoryStage = flowResult.missingStages.firstOrNull { it.mandatory }

        if (mandatoryStage != null) {
            doStage(mandatoryStage)
        } else {
            // Consider optional stages
            val optionalStage = flowResult.missingStages.firstOrNull { !it.mandatory && it !is Stage.Dummy }
            if (optionalStage == null) {
                // Should not happen...
            } else {
                doStage(optionalStage)
            }
        }
    }

    private fun doStage(stage: Stage) {
        // Ensure there is no fragment for registration stage in the backstack
        supportFragmentManager.popBackStack(FRAGMENT_REGISTRATION_STAGE_TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        when (stage) {
            is Stage.ReCaptcha -> activity.addFragmentToBackstack(views.loginFragmentContainer,
                    LoginCaptchaFragment::class.java,
                    LoginCaptchaFragmentArgument(stage.publicKey),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            is Stage.Email     -> activity.addFragmentToBackstack(views.loginFragmentContainer,
                    LoginGenericTextInputFormFragment::class.java,
                    LoginGenericTextInputFormFragmentArgument(TextInputFormFragmentMode.SetEmail, stage.mandatory),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            is Stage.Msisdn    -> activity.addFragmentToBackstack(views.loginFragmentContainer,
                    LoginGenericTextInputFormFragment::class.java,
                    LoginGenericTextInputFormFragmentArgument(TextInputFormFragmentMode.SetMsisdn, stage.mandatory),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            is Stage.Terms     -> activity.addFragmentToBackstack(views.loginFragmentContainer,
                    LoginTermsFragment::class.java,
                    LoginTermsFragmentArgument(stage.policies.toLocalizedLoginTerms(activity.getString(R.string.resources_language))),
                    tag = FRAGMENT_REGISTRATION_STAGE_TAG,
                    option = commonOption)
            else               -> Unit // Should not happen
        }
    }
}
