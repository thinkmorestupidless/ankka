import { useEffect, useState, type FormEvent } from "react";
import { addItem, readCart, readSummary, type Shown } from "./api.ts";

const cart = "demo";

/** Adds an item to a cart through the mount, and shows the cart and the total the server read. */
export function App() {
  const [contents, setContents] = useState<Shown | undefined>();
  const [summary, setSummary] = useState<Shown | undefined>();
  const [name, setName] = useState("Tea");

  async function refresh() {
    setContents(await readCart(cart));
    setSummary(await readSummary(cart));
  }

  useEffect(() => {
    void refresh();
  }, []);

  async function add(event: FormEvent) {
    event.preventDefault();
    await addItem(cart, { productId: name.toLowerCase().replace(/[^a-z0-9]+/g, "-"), name, quantity: 1 });
    await refresh();
  }

  return (
    <main>
      <h1>The shopping cart</h1>
      <form onSubmit={add}>
        <label>
          Item <input value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <button type="submit">Add one</button>
      </form>
      <section>
        <h2>The cart, from the service "cart" under /api/cart</h2>
        <pre data-testid="cart">{contents ? contents.text : "asking…"}</pre>
      </section>
      <section>
        <h2>Its total, as this interface's server read it</h2>
        <p data-testid="summary">{summary ? summary.text : "asking…"}</p>
      </section>
    </main>
  );
}
