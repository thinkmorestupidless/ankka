/** What the app shows for one read or write: what answered, or why nothing did. */
export interface Shown {
  ok: boolean;
  text: string;
}

async function shown(path: string, answer: Promise<Response>): Promise<Shown> {
  try {
    const response = await answer;
    const text = await response.text();
    return response.ok ? { ok: true, text } : { ok: false, text: `${path} answered ${response.status}: ${text}` };
  } catch {
    return { ok: false, text: `${path} did not answer` };
  }
}

/**
 * The cart, under the mount at /api/cart: the browser reaches the service "cart" at the interface's
 * own address, through the platform's proxy, and the cart needs no address of its own.
 */
export function readCart(cart: string, fetchImpl: typeof fetch = fetch, base = ""): Promise<Shown> {
  const path = `${base}/api/cart/carts/${encodeURIComponent(cart)}`;
  return shown(path, fetchImpl(path));
}

export function addItem(
  cart: string,
  item: { productId: string; name: string; quantity: number },
  fetchImpl: typeof fetch = fetch,
  base = "",
): Promise<Shown> {
  const path = `${base}/api/cart/carts/${encodeURIComponent(cart)}/items`;
  return shown(path, fetchImpl(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(item) }));
}

/** The cart's total, as this project's server read it from the cart at the calling address. */
export function readSummary(cart: string, fetchImpl: typeof fetch = fetch, base = ""): Promise<Shown> {
  const path = `${base}/summary?cart=${encodeURIComponent(cart)}`;
  return shown(path, fetchImpl(path));
}
