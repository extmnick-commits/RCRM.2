# RCRM Android Project Rules (AGENTS.md)

## 1. Tech Stack & Architecture
- **Platform:** Native Android Application (Kotlin, Gradle Kotlin DSL `build.gradle.kts`).
- **Packages:**
  - Core Activities, Receivers, Workers, and Adapters live in `com.example.nickpulido`.
  - Jetpack Compose UI components and theme files live in `com.nickpulido.rcrm.ui`.
- **UI Paradigm:** Hybrid XML Layouts (`app/src/main/res/layout/*.xml`) for core Activities/Dialogs (`MainActivity`, `AppointmentsActivity`, `FollowUpActivity`, `ContactsActivity`, `PipelinesActivity`, `SmartLogActivity`, `SideKickActivity`) combined with Jetpack Compose (`DashboardScreen.kt`, `PipelinesScreen.kt`, `Components.kt`).
- **Backend & Storage:** Firebase (Firestore / Auth / `google-services.json`), `SharedPreferences` for local state/settings, and Android `ContentResolver` (`CalendarContract`, `ContactsContract`).

## 2. Design System & Styling Rules
- **Visual Identity:** Space-Glass & Neon Dark Theme.
- **Reusable Drawables:** Always reuse existing drawables in `res/drawable/` instead of inventing missing assets:
  - Cards & Dialogs: `@drawable/bg_frosted_card`, `@drawable/bg_dialog_space_glass`, `@drawable/card_background`
  - Inputs & Pills: `@drawable/bg_frosted_pill_input`, `@drawable/bg_edittext_outline`, `@drawable/bg_tab_container_pill`
  - Buttons: `@drawable/bg_neon_cyan_button`, `@drawable/bg_neon_purple_button`, `@drawable/bg_neon_green_button`, `@drawable/bg_primary_gradient_button`
  - Icon Badges: `@drawable/bg_icon_circle_cyan`, `@drawable/bg_icon_circle_purple`, `@drawable/bg_icon_circle_blue`, `@drawable/bg_icon_circle_green`

## 3. Coding Standards
- **Zero Placeholders:** Never leave `// TODO` or truncated code blocks. Write complete, compilable Kotlin and XML.
- **Permissions & Intents:** Use modern `ActivityResultContracts.RequestMultiplePermissions()` for runtime permissions and wrap external `startActivity` calls in `try/catch` blocks so the app never crashes if an external app is missing.
- **Strict Isolation:** Do NOT generate React, Next.js, TypeScript, or web HTML/CSS in this repository.