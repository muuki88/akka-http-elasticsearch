# Akka HTTP with Elasticsearch streaming

How to stream data from elasticsearch to a websocket and the other way round.


## Requirements

- Running elasticsearch on `localhost:9300`
- [wscat](https://www.npmjs.com/package/wscat). Optional, but is used in the descriptions below.

## Run

```
sbt run
```

## Features

The server has three endpoints to demonstrate different use cases

### /es/insert

Inserting data into elasticsearch. First connect to the websocket with

```
wscat -c ws://localhost:9000/es/insert
```

Now you can insert data in the following scheme `id:title:body`. E.g.

```
> 1;My title;My body
< Inserted Question(1,My title,My body)
```

### /es/get

This endpoint will take a search query and streams all questions back
to the websocket client. Connect with

```
wscat -c ws://localhost:9000/es/get
```

You should have inserted some data before, so you can actually search.

```
> test
< Question(130,test,test)
< Question(125,Test title,test body)
< Question(126,Another test title,test body)
```

If your websocket client is too slow, backpressure will automatically be applied.

### /es/scroll

This endpoint  doesn't stream all results immediately, but allows you to call `next`
in order to get the next results. The query-api is simple

- `n:[<Int>]` for the next _n_ results, e.g. `n:10` or simply `n` for the next result.
- `s:<String>` to start a new search, e.g. `n:test`

Connect with

```
wscat -c ws://localhost:9000/es/scroll
```

and start your query (the first two results will be send by default)

```
> s:test
< Question(130,test,test)
< Question(125,Test title,test body)
> n
< Question(126,Another test title,test body)
> n
```

> Note: Currently if you start multiple searchs all results will be send and the last search isn't canceled.
