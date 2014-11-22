require('./../integration/integration_config.js');

var request = require('request');
var http = require('http');
var channelName = utils.randomChannelName();
var groupName = utils.randomChannelName();
var channelResource = channelUrl + "/" + channelName;
var testName = __filename;
var port = callbackPort + 2;
var callbackUrl = callbackDomain + ':' + port + '/';
var groupConfig = {
    callbackUrl : callbackUrl,
    channelUrl : channelResource
};

/**
 * This should:
 *
 * 1 - create a channel
 * 2 - post items into the channel
 * 3 - verify that records are returned via time query
 */
describe(testName, function () {
    utils.createChannel(channelName);

    it('queries before insertion', function (done) {
        request.get({url : channelResource + '/time/minute', json : true},
            function (err, response, body) {
                expect(err).toBeNull();
                expect(response.statusCode).toBe(200);
                expect(body._links.uris.length).toBe(0);
                done();
            })
    });

    for (var i = 0; i < 4; i++) {
        utils.addItem(channelResource);
    }

    //todo - gfm - 11/5/14 - tests for different resolutions
    //todo - gfm - 11/5/14 - tests for next/previous
    //todo - gfm - 11/19/14 - test for ordering

    it('gets items from channel minute', function (done) {
        request.get({url : channelResource + '/time/minute', json : true },
            function (err, response, body) {
                expect(err).toBeNull();
                expect(response.statusCode).toBe(200);
                expect(body._links.uris.length).toBe(4);
                //todo - gfm - 11/4/14 - do a get on these
                done();
            })
    });

    it('gets items from channel hour', function (done) {
        request.get({url : channelResource + '/time/hour', json : true},
            function (err, response, body) {
                expect(err).toBeNull();
                expect(response.statusCode).toBe(200);
                expect(body._links.uris.length).toBe(4);
                //todo - gfm - 11/4/14 - do a get on these
                done();
            })
    });

});

