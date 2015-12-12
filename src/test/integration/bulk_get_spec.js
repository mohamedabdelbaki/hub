require('./integration_config.js');

var request = require('request');
var Q = require('q');
var channelName = utils.randomChannelName();
var channelResource = channelUrl + "/" + channelName;
var testName = __filename;
utils.configureFrisby();


/**
 * create a channel
 * post 4 items
 * stream items back with bulk API from all query endpoints, using bulk & batch params
 */
describe(testName, function () {

    utils.putChannel(channelName, false, {"name": channelName, "ttlDays": 1, "tags": ["bulk"]});

    var multipart =
        'This is a message with multiple parts in MIME format.  This section is ignored.\r\n' +
        '--abcdefg\r\n' +
        'Content-Type: text/plain\r\n' +
        ' \r\n' +
        'message one\r\n' +
        '--abcdefg\r\n' +
        'Content-Type: text/plain\r\n' +
        ' \r\n' +
        'message two\r\n' +
        '--abcdefg\r\n' +
        'Content-Type: text/plain\r\n' +
        ' \r\n' +
        'message three\r\n' +
        '--abcdefg\r\n' +
        'Content-Type: text/plain\r\n' +
        ' \r\n' +
        'message four\r\n' +
        '--abcdefg--'

    var items = [];

    it("batches items to " + channelResource, function (done) {
        request.post({
                url: channelResource + '/bulk',
                headers: {'Content-Type': "multipart/mixed; boundary=abcdefg"},
                body: multipart
            },
            function (err, response, body) {
                expect(err).toBeNull();
                expect(response.statusCode).toBe(201);
                var parse = utils.parseJson(response, testName);
                console.log(response.body);
                expect(parse._links.uris.length).toBe(4);
                items = parse._links.uris;
                done();
            });
    });

    function getQ(url, verifyFunction, accept) {
        var deferred = Q.defer();
        request.get({
                url: url,
                followRedirect: true,
                headers: {Accept: accept}
            },
            function (err, response, body) {
                expect(err).toBeNull();
                expect(response.statusCode).toBe(200);
                verifyFunction(response);
                deferred.resolve({response: response, body: body});
            });
        return deferred.promise;
    }

    function getAll(url, done, verifyFunction) {
        verifyFunction = verifyFunction || function (response) {
                for (var i = 0; i < items.length; i++) {
                    expect(response.body.indexOf(items[i]) > 0).toBe(true);
                }
            }
        var verifyZip = function () {
        };
        url = url + '?stable=false';
        getQ(url + '&bulk=true', verifyFunction, "multipart/mixed")
            .then(function (value) {
                return getQ(url + '&batch=true', verifyFunction, "multipart/mixed");
            })
            .then(function (value) {
                return getQ(url + '&bulk=true', verifyZip, "application/zip");
            })
            .then(function (value) {
                return getQ(url + '&batch=true', verifyZip, "application/zip");
            })
            .then(function (value) {
                done();
            })
    }

    function sliceFromEnd(chars) {
        return items[0].slice(0, items[0].length - chars);
    }

    it("gets latest bulk ", function (done) {
        getAll(channelResource + '/latest/10', done);
    });

    it("gets earliest items ", function (done) {
        getAll(channelResource + '/earliest/10', done);
    });

    it("gets day items ", function (done) {
        getAll(sliceFromEnd(26), done);
    });

    it("gets hour items ", function (done) {
        getAll(sliceFromEnd(23), done);
    });

    it("gets minute items ", function (done) {
        getAll(sliceFromEnd(20), done);
    });

    it("gets second items ", function (done) {
        getAll(sliceFromEnd(17), done);
    });

    it("gets next items ", function (done) {
        getAll(items[0] + '/next/10', done, function (response) {
            for (var i = 1; i < items.length; i++) {
                expect(response.body.indexOf(items[i]) > 0).toBe(true);
            }
            var linkHeader = response.headers['link'];
            expect(linkHeader).toBeDefined();
            expect(linkHeader).toContain(items[1] + '/previous/10?bulk=true');
            expect(linkHeader).toContain(items[3] + '/next/10?bulk=true');
        });
    });

    it("gets previous items ", function (done) {
        getAll(items[3] + '/previous/10', done, function (response) {
            for (var i = 0; i < items.length - 1; i++) {
                expect(response.body.indexOf(items[i]) > 0).toBe(true);
            }
            var linkHeader = response.headers['link'];
            expect(linkHeader).toBeDefined();
            expect(linkHeader).toContain(items[0] + '/previous/10?bulk=true');
            expect(linkHeader).toContain(items[2] + '/next/10?bulk=true');
        });
    });

});
