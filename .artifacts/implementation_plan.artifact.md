# RCRM Dark-Mode & Glassmorphism Facelift

Based on the target UI design, you are aiming for a highly polished, modern aesthetic featuring:
1.  **Deep dark space-blue/black backgrounds.**
2.  **Neon glowing accents (Cyan, Green, Pink/Red).**
3.  **Translucent glassmorphic surfaces (panels and cards).**
4.  **Complex gradients and circular progress indicators.**

## User Review Required

> [!WARNING]
> **XML vs. Jetpack Compose for Neon & Glassmorphism**
>
> You requested to stop and advise on a Compose migration strategy if it is significantly cleaner. **It absolutely is.**
>
> **Why XML is problematic for this design:**
> *   **Neon Glows (Colored Shadows):** Standard XML `<shape>` drawables do not support colored drop shadows natively. You have to use `outlineSpotShadowColor` (API 28+ only, often too subtle), rely on external image assets (9-patches), or use complex layered XML drawables that only approximate the effect (stacking translucent strokes).
> *   **Glassmorphism (Background Blur):** Real background blur in XML is only natively supported on Android 12+ (API 31) via `RenderEffect`. For older versions, it requires third-party libraries or falling back to simple semi-transparent colors without the blur effect.
>
> **Why Jetpack Compose is better here:**
> *   **Custom Drawing:** Compose allows easy custom Modifiers (e.g., `Modifier.drawBehind { drawRoundRect(..., paint.apply { setShadowLayer(..., color) }) }`) which perfectly replicate neon glowing borders across all API levels.
> *   **Declarative Styling:** Applying global glassmorphism and gradients is significantly less boilerplate than managing dozens of XML drawable files.

### Proposed Jetpack Compose Migration Strategy

Since rewriting all 24+ layout XMLs and their backing ViewBinding code at once is a massive undertaking, the safest strategy is an **Incremental Migration using `ComposeView`**:

1.  **Phase 1: Setup & Design System (Compose)**
    *   Enable Jetpack Compose in `build.gradle.kts`.
    *   Create a `Theme.kt`, `Color.kt`, and `Shape.kt` defining the exact space-blue palette, gradients, and typography.
    *   Build reusable Compose components: `NeonCard`, `GlassButton`, `GlowingProgressIndicator`.
2.  **Phase 2: Hybrid Integration (The "Strangler Fig" pattern)**
    *   We don't need to rewrite the entire XML files immediately. We can replace specific complex elements (like the Action Grid or the Lead Cards in the RecyclerView) by embedding a `<androidx.compose.ui.platform.ComposeView>` in the existing XML.
    *   The `RecyclerView` adapters can be updated to return a `ComposeView` that hosts the highly stylized `LeadCard` composable.
3.  **Phase 3: Screen-by-Screen Replacement (Optional/Later)**
    *   Gradually replace entire XML layouts with pure Compose functions starting with the most complex (e.g., `activity_main.xml`).

## Open Questions

> [!IMPORTANT]
> **How would you like to proceed?**
>
> **Option A (Recommended): Proceed with the Compose Migration Strategy.** I will first set up Compose and the Compose-based Design System, then we can start replacing the most prominent UI elements (like the Dashboard and RecyclerView items) using `ComposeView`.
>
> **Option B: Stick to XML Views.** I will do my best to approximate the look using layered XML drawables, standard elevations, and semi-transparent colors (without true blur or perfect colored shadows on older APIs).

Please let me know which option you prefer before we begin modifying the codebase.
