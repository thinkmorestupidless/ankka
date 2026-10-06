/**
 * The button recipe: the classes for a button, or a link drawn as one, by variant and size. In the
 * shape of shadcn's recipes, drawn on the console's own `ac-` component classes so a host keeps the
 * class names the documentation promises.
 */
import { cva, type VariantProps } from "class-variance-authority";

export const button = cva("ac-button", {
  variants: {
    variant: {
      default: "",
      primary: "ac-button-primary",
      danger: "ac-button-danger",
      quiet: "ac-button-quiet",
    },
    size: {
      default: "",
      small: "ac-button-small",
    },
  },
  defaultVariants: { variant: "default", size: "default" },
});

export type ButtonVariants = VariantProps<typeof button>;
