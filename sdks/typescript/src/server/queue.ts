// A small async queue: producers push, one consumer iterates. Used where replies from more than one
// source leave through one stream (a workflow's commands and its running step), and where a handler's
// output must be produced inside a request's async context but consumed outside it.

export class AsyncQueue<T> implements AsyncIterable<T> {
  readonly #items: T[] = []
  #waiting: ((r: IteratorResult<T>) => void) | undefined
  #closed = false
  #failure: unknown

  push(item: T): void {
    if (this.#closed) return
    if (this.#waiting) {
      const w = this.#waiting
      this.#waiting = undefined
      w({ value: item, done: false })
    } else {
      this.#items.push(item)
    }
  }

  /** Ends the iteration once the queued items are drained. */
  close(): void {
    this.#closed = true
    if (this.#waiting && this.#items.length === 0) {
      const w = this.#waiting
      this.#waiting = undefined
      w({ value: undefined as T, done: true })
    }
  }

  /** Ends the iteration with an error once the queued items are drained. */
  fail(error: unknown): void {
    this.#failure = error
    this.close()
  }

  get closed(): boolean {
    return this.#closed
  }

  [Symbol.asyncIterator](): AsyncIterator<T> {
    return {
      next: (): Promise<IteratorResult<T>> => {
        if (this.#items.length > 0) return Promise.resolve({ value: this.#items.shift() as T, done: false })
        if (this.#closed) {
          if (this.#failure !== undefined) {
            const f = this.#failure
            this.#failure = undefined
            return Promise.reject(f)
          }
          return Promise.resolve({ value: undefined as T, done: true })
        }
        return new Promise((resolve) => {
          this.#waiting = resolve
        })
      },
      return: (): Promise<IteratorResult<T>> => {
        this.#closed = true
        return Promise.resolve({ value: undefined as T, done: true })
      },
      // Connect's bidi client requires the request iterable to implement `throw`.
      throw: (error: unknown): Promise<IteratorResult<T>> => {
        this.#closed = true
        this.#items.length = 0
        return Promise.reject(error)
      },
    }
  }
}
