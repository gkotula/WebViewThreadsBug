# What is this?

A quick project to demonstrate seemingly unbounded thread creation in an edge case use of
`WebViewClient.shouldInterceptRequest()`.

## How to reproduce

1. Bulid and run the app.
1. Upon launch, you should see a simple WebView with the text "Hello, World!" displayed.
1. Open the Chrome Inspector on your desktop, and inspect the WebView.
1. In the console, enter the following:
```javascript
function appendNewElement(id) {
    let elem = document.createElement('script');
    elem.setAttribute('src', 'https://foo/' + id);
    document.head.appendChild(elem);
}
```
1. Start a Perfetto trace on the device.
1. Enter the following in the console:
```javascript
for (let i = 0; i < 100; i++) {
    appendNewElement(i);
}
```

At this point, the app is intercepting the script tag src requests and _blocking_ the calling thread
for 30s _before_ returning a `WebResourceResponse`. Wait until all of the script tags have been
loaded by looking for the "Script loaded" console log, and then stop the trace.

## Interpreting the results

Opening the Perfetto trace and searching for `MyTag_response`, will show that there are 100 traces,
each one corresponding to one of the `WebResourceRequests` for the injected script tags.
**Critically**, each `WebResourceRequest` will be run on _its own thread_, indicating that the
WebView is spawning new threads to handle each request, seemingly without bound.

### Observations

It looks like the WebView begins with six worker threads that handle these async resource requests.
In the trace you will observe the first group of six `MyTag_response` traces start within a couple
of milliseconds of each other, but after about 1.2 seconds, _another_ group of six threads is
created while the first group of six is blocked. Each of these new threads again begins the request
within a couple of milliseconds of each other, but then after another ~1.2 seconds, the _next_ group
of six threads is spawned. This repeats ad-infinitum or until all the resource requests have been
fulfilled.

From these observations, I can hypothesize that by design there is a pool of six threads that is
dedicated to loading up to six async requests concurrently, but something about blocking the thread
before getting a response is causing a new thread pool to be spun up.

## Expected behavior

I have a hunch that this thread creation was not expected behavior, so I added another test case to
the project that showed very different results. In the `MainActivity` you will see two different
methods to construct the `WebViewClient`; the default setup will show you the thread spawning
behavior, but repeating the steps above with the other setup shows what I think is expected
behavior.

In the second setup, the thread blocking is moved to _within the `InputStream.read` method_, but the
`WebViewResponse` is returned quickly. When you take the trace using this setup (and searching for
`MyTag_read`) the results show that _no new threads are spawned beyond the starting six_. Each
`MyTag_read` trace stays within one of the six original threads, and this is the behavior I expect.
If a resource takes a long time to receive data from a blocking I/O operation, the worker thread
doing the loading should simply wait for the data to arrive, allowing up to the thread pool maximum
concurrent requests to finish before starting any other work.

### More hypothesis

`InputStream.read` is inherently a blocking operation backed by potentially a slow network
connection. It seems logical that a worker thread reading from this blocking I/O would be
implemented to wait in a blocking fashion for data to arrive. I expect that that same thread being
blocked from getting the response object would behave the same way, just waiting until the response
arrives, but as we can see from the traces, blocking the response causes the WebView to spawn new
threads.

This is my hypothesis: something about blocking the response is causing the undesirable behavior of
new threads being spawned without bound, and I would expect that blocking the response would behave
the same as blocking the first byte read from the response.
