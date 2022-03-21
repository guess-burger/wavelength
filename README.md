# wavelength

FIXME: my new application.


## Running

Spin up a repl using `clj -M:repl/run` and that _should_ start the server on 8080

You probably need a cljs build step _somewhere_ but using `clj -M:repl/fig` for now to run fig-wheel

## TODO

* REALLY need to figure out how the lobby Inversion-of-Control stuff should work
  * lobby can't start the state machine
  * Does it even need to track lobbies (can the lobbies atom move outside)?
    * Which opens up more way for a lobby to be join to be honest
    * Can just have a join and create function then
    * ... but with the join function needing to send a msg
    * ... and the create relying on the first-entry-fx to send?
* Allow "start game" from the lobby
  * get the server to let the players know when the game can start
  * Get the server checking if the game can start
* Move the whole BiDi channel stuff out of the lobby handler (just to handle chord being naff
  * Maybe we can just pass an xform to stately... but then you'd need to know which channels need which xform
    * but maybe that be done in the inputs since that's a little bare at the minute
  * Still, BiDi is kind of expected by stately...
    * ... or is it? It just cares about inputs, nothing stopping you sending to something in the context already

...

### Bugs

...

## License

Copyright © 2022 Gavinesberger

Distributed under the Eclipse Public License version 1.0.
