/**
 * The OpsPilot mark: a navigator's arrow inside a rounded tile.
 *
 * Inline SVG rather than an image file so it inherits `currentColor` and needs
 * no extra network request or dark-mode variant. `aria-hidden` because the
 * wordmark next to it already carries the name — a screen reader announcing
 * "OpsPilot OpsPilot" is worse than no alt text here.
 */
export function BrandMark({ size = 24 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <rect width="24" height="24" rx="7" fill="url(#opspilot-mark)" />
      <path
        d="M12 5.5 17.2 18 12 15.1 6.8 18 12 5.5Z"
        fill="#fff"
        fillOpacity="0.95"
        stroke="#fff"
        strokeWidth="0.6"
        strokeLinejoin="round"
      />
      <defs>
        <linearGradient id="opspilot-mark" x1="0" y1="0" x2="24" y2="24">
          <stop stopColor="#176B7D" />
          <stop offset="1" stopColor="#0A2E3A" />
        </linearGradient>
      </defs>
    </svg>
  );
}
