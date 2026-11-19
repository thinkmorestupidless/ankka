// A typed client of the Scala shopping cart, written against the types openapi-typescript generates
// from the cart's kept OpenAPI document (`samples/shopping-cart/openapi.json`) into `cart.d.ts`. CI
// generates them and type-checks this file with `tsconfig.json` beside it: a route, a body or an
// answer the document stops describing as this client uses it is a type error.
import type { components, paths } from "./cart.d.ts";

type LineItem = components["schemas"]["LineItem"];
type Cart =
  paths["/carts/{cartId}"]["get"]["responses"]["200"]["content"]["application/json"];
type AddedBody = NonNullable<
  paths["/carts/{cartId}/items"]["post"]["requestBody"]
>["content"]["application/json"];

/** Adds one line to a cart: `POST /carts/{cartId}/items`, answered 204. */
export async function addItem(base: string, cartId: string, item: AddedBody): Promise<void> {
  const response = await fetch(`${base}/carts/${encodeURIComponent(cartId)}/items`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(item),
  });
  if (response.status !== 204) throw new Error(`add item: ${response.status} ${await response.text()}`);
}

/** Reads a cart: `GET /carts/{cartId}`. */
export async function getCart(base: string, cartId: string): Promise<Cart> {
  const response = await fetch(`${base}/carts/${encodeURIComponent(cartId)}`);
  if (response.status !== 200) throw new Error(`get cart: ${response.status} ${await response.text()}`);
  return (await response.json()) as Cart;
}

/** What a caller writes with the two: a line added, then the quantities read back. */
export async function quantityOf(base: string, cartId: string, line: LineItem): Promise<number> {
  await addItem(base, cartId, line);
  const cart = await getCart(base, cartId);
  // A collection is not required: the codec reads a missing one as empty.
  return (cart.items ?? []).filter((i) => i.productId === line.productId).reduce((n, i) => n + i.quantity, 0);
}
