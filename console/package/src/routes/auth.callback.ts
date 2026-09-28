export async function loader() {
  return new Response("callback", { status: 200 });
}
