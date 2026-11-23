# Aistee Design

## Overview

Aistee is an AI workbench with account-backed WebView chats, native provider chats, playground/tools and adaptive navigation. Its UI should feel like a serious developer tool that happens to be comfortable on a phone: dark-first, dense where density helps, quiet around long conversations.

The canonical runtime visual tokens live in `app/src/main/java/ais/tee/ui/theme/`. This file documents their intent and must change with them when a durable design decision changes.

**North Star:** long-chat readability and fast provider/tool switching without neon-dashboard noise.

**Avoid:** cyberpunk decoration, giant gradients, per-provider rainbow theming, gratuitous blur, animated backgrounds and visual effects that increase recomposition or GPU cost.

## Colors

Preserve the established semantic palette:

- dark primary indigo: `#818CF8`
- dark secondary cyan: `#38BDF8`
- dark tertiary violet: `#A78BFA`
- dark background: `#0F172A`
- dark surface: `#1E293B`
- dark surface variant: `#334155`
- light primary: `#4F46E5`
- light background: `#F8FAFC`
- light surface: `#FFFFFF`

Existing emerald, amber and rose accents are semantic state colors. They should not become general decoration or provider branding.

Dark is the product-default theme; light remains a complete supported companion.

## Typography

Keep the existing system-font type scale. It is intentionally compact for a tool UI:

- display: 26–32sp, bold
- title: 16–20sp, semibold
- body: 13–15sp
- labels: 11–14sp

Chat message text and code must prioritize line length, selection and scrolling stability over decorative typography. Use monospace only for actual code, identifiers or data where fixed-width presentation helps.

## Layout

Aistee already owns adaptive navigation. Let the Material 3 Adaptive shell choose bottom navigation, compact alternatives or rail behavior from available width instead of building duplicate screens.

Chat surfaces should keep the composer reachable, preserve message width that remains readable on large screens, and avoid moving controls during streaming. Tool/playground screens may be denser than chats when the information model benefits.

Account-backed WebViews are product-priority surfaces and must remain platform-aware rather than being wrapped in artificial visual abstractions.

## Elevation & Depth

Dark surfaces separate primarily through tone and borders. Use shadows sparingly for transient layers such as sheets, menus and floating controls. Avoid stacking elevated cards inside elevated chat containers.

## Shapes

Runtime shape scale:

- extra small: 6dp
- small: 8dp
- medium: 12dp
- large: 18dp
- extra large: 24dp

This is intentionally tighter than consumer-media apps. Pills are for chips, provider/model selectors and compact state controls.

## Components

Material 3 and existing shared Compose primitives remain canonical.

- Chat composer: stable geometry during send/stream/error states.
- Messages: quiet surfaces with strong text contrast; do not color every provider differently.
- Provider/model selectors: compact, explicit state, accessible labels.
- Navigation: preserve the existing adaptive navigation suite.
- WebView shell: native page content stays visually distinct from app-owned chrome; do not fake provider pages.
- Status colors: emerald success/connected, amber caution, rose error/destructive, with text/icon semantics in addition to color.

## Do's and Don'ts

Do optimize for long sessions, bounded WebViews, stable scrolling, accessible dark contrast and adaptive layouts.

Do not add third-party UI frameworks or animation libraries without a concrete missing capability. Do not trade WebView lifecycle discipline, memory behavior or streaming performance for visual polish.
