# Wavelength

A simple online port of the [Wavelength board game](https://www.wavelength.zone/).

You can [try it online](https://mysterious-basin-71031.onrender.com)

This project was originally a way to get more familiar with Clojure core.async.
If you are interest, you can read more about that elsewhere in this project.

## Running Locally

```bash
# Build the ClojureScript (first time only)
clj -M:fig:min 
# Run the Server
clj -M:run 
```
This starts the server on port 8080 by default and will allow you to play the game.

### Local Development

If you would to run the game through a REPL then run `clj -A:dev` and `clj -M:fig:repl`
in separate terminals.

Inside `dev/dev.clj`, you'll find some helper functions to rebuild the "Wavelengths" used
in this game by using the hard work done by another port, [Telewave](https://github.com/gjeuken/telewave).

### Uberjar

An uberjar be produced containing `cljs` code via `clj -T:build uber`

## Building for ~~Heroku~~ Render

This project now makes use of [Render](https://render.com/) to deploy. It uses the `render.yaml` configure the service and the 
`Dockerfile.render` to build and then run service.

## License

Copyright © 2022 guess-burger

Distributed under the Eclipse Public License version 1.0.
