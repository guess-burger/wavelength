# wavelength

FIXME: my new application.


## Running

Spin up a repl using `clj -M:repl/run` and that _should_ start the server on 8080

You probably need a cljs build step _somewhere_ but using `clj -M:repl/fig` for now to run fig-wheel

## TODO

### NEXT
* [_] Psychic Phase
  * [X] Pick a psychic
    * [X] disconnects (to fix the disconnects bug). Go back to pick
  * [X] let them pick a card
  * [_] let them add a clue
* [_] Guessing Phase
* [_] L/R Phase


### Things to keep an eye on

* I ended up letting the lobby start the game _but_ you pass the game to the lobby.
  * There was a bit too much going on with the lobby knowing to send the lobby code out to players for me to lift
    that information out
* spectators should be able to join at any point really...
  * So does that mean we need to break joining and the lobbies atom out of the lobbie ns?
  * TODO: Let's ignore this for now and handle it later once things re working

### Bugs

* No inputs to stately cause hot-loop if there is a default that recurs
  * I mean what does it mean have no inputs... that seems an error
  * Actually... is it that the real problem or it that it now null since they are closed
    * probably need to figure out leaving asap and make it reusable 

### LATER
* Allow spectators to join during the game

...

## License

Copyright © 2022 Gavinesberger

Distributed under the Eclipse Public License version 1.0.
