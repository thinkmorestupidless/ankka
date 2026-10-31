package {{package}}.{{service_pkg}}

/** Starts {{service}}: everything it hosts is registered in `{{Service}}Service`. */
@main def run(): Unit = {{Service}}Service.start()
