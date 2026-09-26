## Figma Design Tokens

Source: [Aquarium Store Complete Landing Page](https://www.figma.com/design/73pfruw9E2XFRQO0H56JaZ/Mizuhito-Sakamichi-s-team-library?node-id=5311-2)

The tokens below are extracted from the Figma landing-page design and should be reused across the storefront UI.

## Typography

### Primary Typeface

- Font family: `Plus Jakarta Sans`
- Weights used:
	- Regular: `400`
	- Medium: `500`
	- SemiBold: `600`
	- Bold: `700`
	- ExtraBold: `800`
- Secondary glyph font: `Liberation Sans Bold` for the arrow character used in category links.

### Type Scale

| Role                  | Weight | Size | Line height      | Letter spacing |
| ---                   | ---:   | ---: | ---:             | ---:           |
| Hero heading          | 800    | 60px | 60px             | -1.5px         |
| Section heading       | 800    | 48px | 48px             | -1.2px         |
| Community metric      | 800    | 36px | 40px             | 0              |
| Category heading      | 700    | 30px | 36px             | -0.75px        |
| Card heading          | 700    | 20px | 28px             | 0              |
| Body text             | 400    | 18px | 29.25px          | 0              |
| Body text compact     | 400    | 16px | 24px             | 0              |
| Navigation text       | 500    | 15px | 22.5px           | 0              |
| Small body text       | 400    | 14px | 20px or 22.75px  | 0              |
| Eyebrow and labels    | 700    | 12px | 16px             | 1.2px          |

Use uppercase text for eyebrow labels and apply `letter-spacing: 1.2px`.

## Colors

### Brand and Surface Colors

| Token                 | Hex       | Usage                                                                             |
| ---                   | ---       | ---                                                                               |
| `brand-teal`          | `#1BC5BD` | Primary hero and community call-to-action buttons, highlighted hero text, metrics |
| `brand-aqua`          | `#2DD4BF` | Eyebrow labels, footer headings                                                   |
| `brand-aqua-light`    | `#5EEAD4` | Category card links and item counts                                               |
| `brand-deep-teal`     | `#115E59` | Brand logo, product prices, cart actions                                          |
| `brand-dark-teal`     | `#0E443E` | Header and newsletter action buttons                                              |
| `surface-page`        | `#F2F7F5` | Main page and content-section background                                          |
| `surface-white`       | `#FFFFFF` | Header, product cards, feature cards, inputs                                      |
| `surface-icon`        | `#EDF6F4` | Feature-card icon backgrounds                                                     |
| `surface-dark`        | `#0B2926` | Community banner and hero-adjacent dark sections                                  |
| `surface-footer`      | `#071F1D` | Footer background                                                                 |
| `surface-metric`      | `#123631` | Community metric cards                                                            |
| `surface-badge`       | `#0F172A` | Circular footer badge                                                             |

### Text Colors

| Token                 | Hex       | Usage                                             |
| ---                   | ---       | ---                                               |
| `text-heading`        | `#0F172A` | Dark headings and primary text on light surfaces  |
| `text-navigation`     | `#334155` | Header navigation links                           |
| `text-body`           | `#475569` | Body copy and supporting text on light surfaces   |
| `text-muted`          | `#64748B` | Secondary descriptions                            |
| `text-placeholder`    | `#94A3B8` | Input placeholders and muted footer text          |
| `text-light`          | `#CBD5E1` | Text on dark surfaces                             |
| `text-card-light`     | `#E2E8F0` | Text over category-card imagery                   | 
| `text-white`          | `#FFFFFF` | High-contrast text on dark surfaces and buttons   |

### Borders and Overlays

| Token                     | Value                                                         | Usage                             |
| ---                       | ---                                                           | ---                               |
| `border-subtle`           | `rgba(226, 232, 240, 0.6)`                                  | Product and feature cards         |
| `border-light`            | `#E2E8F0`                                                     | Product tags                      |
| `border-input`            | `#CBD5E1`                                                     | Newsletter input                  |
| `border-header`           | `#F1F5F9`                                                     | Header bottom border              |
| `border-footer`           | `rgba(19, 78, 74, 0.6)`                                     | Footer divider                    |
| `overlay-button`          | `rgba(255, 255, 255, 0.05)`                                 | Secondary dark-background buttons |
| `overlay-button-border`   | `rgba(255, 255, 255, 0.2)` to `rgba(255, 255, 255, 0.25)` | Secondary button borders          |
| `overlay-image-dark`      | `rgba(9, 38, 35, 0.25)` to `rgba(9, 38, 35, 0.95)`        | Category-card image gradients     |

## Shape and Layout

- Maximum content width: `1280px`.
- Standard page-side padding: `24px`.
- Section vertical padding: generally `96px`.
- Common content gaps: `12px`, `16px`, `24px`, `32px`, `40px`, and `64px`.
- Pill controls and buttons: `9999px` border radius.
- Product and feature cards: `32px` border radius.
- Category image cards: `40px` border radius.
- Feature icon containers: `16px` border radius.
- Header height: `80px`.
- Hero minimum height: `580px`.

## Design Notes

- The visual direction is premium, natural, and aquatic: pale mint content surfaces contrast with deep green sections and bright teal actions.
- Use `Plus Jakarta Sans` consistently for interface text. Reserve `Liberation Sans Bold` for the category arrow glyph only.
- Maintain strong contrast between dark section backgrounds and teal or slate text.
- Preserve the rounded-pill treatment for primary actions and the large rounded corners used on cards.
